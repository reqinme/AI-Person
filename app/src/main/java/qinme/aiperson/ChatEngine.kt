package qinme.aiperson

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 一轮完整的对话：写入用户消息 → 调模型（带回不回/延迟/心情/亲密度）→ 落库。
 */
object ChatEngine {

    data class Result(
        val ok: Boolean,
        val error: String? = null
    )

    private data class Decision(
        val action: String,
        val delayMinutes: Int,
        val reply: String,
        val caption: String,
        val mood: Int?,
        val patience: Int?,
        val intimacyDelta: Int,
        /** 她这一轮想查的东西；空字符串表示不用查。 */
        val search: String = ""
    )

    suspend fun send(characterId: String, text: String, imageName: String = ""): Result =
        withContext(Dispatchers.IO) {
            val db = AppGraph.db
            val now = System.currentTimeMillis()
            val relationship = db.relationshipDao().get(characterId)
                ?: RelationshipEntity(characterId = characterId).also { db.relationshipDao().upsert(it) }
            val messageId = db.messageDao().insert(
                MessageEntity(
                    characterId = characterId,
                    role = "user",
                    content = text.trim(),
                    createdAt = now,
                    imagePath = imageName
                )
            )
            db.relationshipDao().upsert(relationship.copy(lastChatAt = now))

            // 连发合并：人不会每收到一条就回一次。等一小会儿，只让最后一条触发回复。
            val ticket = tickets.getOrPut(characterId) { AtomicLong(0) }.incrementAndGet()
            delay(BURST_WINDOW_MS)
            if (tickets[characterId]?.get() != ticket) return@withContext Result(true)

            // 同一角色同时只允许一轮回复，避免两轮各自读到旧上下文、说出重复的话
            locks.getOrPut(characterId) { Mutex() }.withLock {
                if (tickets[characterId]?.get() != ticket) return@withLock Result(true)
                // 她还没发出去的延迟回复作废，新的一轮统一重答
                db.messageDao().dropPendingReplies(characterId, System.currentTimeMillis())
                generateTurn(characterId, messageId, text, imageName)
            }
        }

    private suspend fun generateTurn(
        characterId: String,
        messageId: Long,
        text: String,
        imageName: String
    ): Result {
            val db = AppGraph.db
            val character = db.characterDao().get(characterId)
                ?: return Result(false, "角色不存在")

            val relationship = db.relationshipDao().get(characterId)
                ?: RelationshipEntity(characterId = characterId).also { db.relationshipDao().upsert(it) }
            val personality = db.personalityDao().get(characterId)
                ?: PersonalityEntity(characterId = characterId).also { db.personalityDao().upsert(it) }
            val mood = db.moodDao().get(characterId)
                ?: MoodEntity(characterId = characterId).also { db.moodDao().upsert(it) }

            val now = System.currentTimeMillis()

            // 连发时几条消息都在这里；她自己刚说的回复也要算进上下文，否则会重复
            val history = db.messageDao().recent(characterId, 40)
                .filter {
                    (it.role == "user" || it.role == "assistant") &&
                        (it.visibleAt == 0L || it.visibleAt <= now + 60_000L)
                }
                .reversed()
            val memories = db.memoryDao().recall(characterId, BehaviorSettings.RECALL_LIMIT)
            // 召回即刷新：这轮被想起的事从当下重新计算衰减
            if (memories.isNotEmpty()) {
                db.memoryDao().touchRecalled(memories.map { it.id }, now)
            }
            val knownDays = ((now - character.createdAt) / TimeUnit.DAYS.toMillis(1)).toInt() + 1
            val totalMessages = db.messageDao().countDialogue(characterId)

            val system = PromptBuilder.chat(
                character,
                relationship,
                personality,
                mood,
                memories,
                knownDays,
                totalMessages
            )

            val raw = try {
                Llm.complete(character, system, history, temperature = 0.8)
            } catch (e: Exception) {
                db.messageDao().insert(
                    MessageEntity(
                        characterId = characterId,
                        role = "system",
                        content = "（连接失败：${e.message ?: "未知错误"}）",
                        createdAt = System.currentTimeMillis()
                    )
                )
                return Result(false, e.message ?: "连接失败")
            }

            val decision = parse(raw).let { first ->
                // 只在她自己觉得需要查的时候才多走一趟；没配搜索就跳过
                if (first.search.isBlank() || !AppGraph.api.searchConfigured) return@let first
                val found = SearchApi.query(first.search) ?: return@let first
                val extra = """
                    补充资料（你刚查到的。不要提来源、不要说"我查了一下"，
                    就像你本来就知道这些，用你自己的方式说出来）：
                    $found
                    请据此重新输出这一轮的完整 JSON，search 保持空字符串。
                """.trimIndent()
                runCatching { Llm.complete(character, system + "\n\n" + extra, history, temperature = 0.8) }
                    .map { parse(it).copy(search = "") }
                    .getOrElse { first }
            }

            // 图片描述记在用户那条消息上，后续记忆压缩会用到
            if (imageName.isNotBlank() && decision.caption.isNotBlank()) {
                db.messageDao().setCaption(messageId, decision.caption.take(120))
            }

            // 心情有惯性：新心情 = 旧 70% + 这一轮 30%；刚吵完的 2-3 轮里只能往下，不能立刻回血
            val hold = BehaviorSettings.moodHold(characterId)
            val blendedMood = Math.round(mood.mood * 0.7f + (decision.mood ?: mood.mood) * 0.3f)
            val blendedPatience = Math.round(mood.patience * 0.7f + (decision.patience ?: mood.patience) * 0.3f)
            val nextMoodValue = if (hold > 0) minOf(blendedMood, mood.mood) else blendedMood
            val nextPatienceValue = if (hold > 0) minOf(blendedPatience, mood.patience) else blendedPatience
            db.moodDao().upsert(
                mood.copy(
                    mood = nextMoodValue.coerceIn(0, 100),
                    patience = nextPatienceValue.coerceIn(0, 100),
                    updatedAt = now
                )
            )
            if (decision.intimacyDelta <= -3) {
                BehaviorSettings.setMoodHold(characterId, 3)
            } else if (hold > 0) {
                BehaviorSettings.setMoodHold(characterId, hold - 1)
            }

            // 亲密度：显示封顶 100，但内部继续累计，避免刚到 100 就被一次踩线打下来
            // 打招呼、寒暄、嗯哦好这类没有信息量的话，不靠模型判断，直接不加分
            val delta = if (decision.intimacyDelta > 0 && isSmallTalk(text, imageName.isNotBlank())) {
                0
            } else {
                decision.intimacyDelta
            }
            val rawIntimacy = BehaviorSettings.intimacyRaw(characterId) ?: relationship.intimacy
            val nextRaw = (rawIntimacy + delta).coerceAtLeast(0)
            val intimacy = nextRaw.coerceAtMost(100)
            BehaviorSettings.setIntimacyRaw(characterId, nextRaw)
            db.relationshipDao().upsert(relationship.copy(intimacy = intimacy, lastChatAt = now))

            val smallTalk = isSmallTalk(text, imageName.isNotBlank())
            val shown = effectivePersonality(personality, intimacy)

            when (decision.action) {
                "silent" -> {
                    // 空的隐藏占位，仅用于推断「已读」，UI 不显示
                    db.messageDao().insert(
                        MessageEntity(
                            characterId = characterId,
                            role = "note",
                            content = "",
                            createdAt = now
                        )
                    )
                }

                "delay" -> {
                    val target = Timing.clampDelay(now, decision.delayMinutes)
                    db.messageDao().insert(
                        MessageEntity(
                            characterId = characterId,
                            role = "note",
                            content = "她看到了，过一会儿回",
                            createdAt = now + 1
                        )
                    )
                    insertReply(characterId, decision.reply, target, notify = true)
                }

                else -> {
                    val reply = decision.reply.ifBlank { "……" }
                    val naturalDelay = naturalDelayMinutes(shown, nextMoodValue, intimacy, smallTalk)
                    if (naturalDelay > 0) {
                        // 按性格慢半拍：清冷、忙、心情差的时候不会秒回
                        val target = Timing.shortDelay(now, naturalDelay)
                        db.messageDao().insert(
                            MessageEntity(
                                characterId = characterId,
                                role = "note",
                                content = "",
                                createdAt = now + 1
                            )
                        )
                        insertReply(characterId, reply, target, notify = true)
                    } else {
                        insertReply(
                            characterId, reply, System.currentTimeMillis(), notify = false
                        )
                    }
                }
            }

            // 记忆压缩会再调一次模型，不能阻塞本轮发送；后台跑，失败下次再补
            growthScope.launch { runCatching { GrowthEngine.maybeGrow(characterId) } }
            return Result(true)
    }

    private val growthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一角色的回复串行执行，避免两轮同时跑。 */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    /** 连发计数：最后一条消息才负责触发回复。 */
    private val tickets = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private const val BURST_WINDOW_MS = 2_500L

    /**
     * 回复拆成一到三条气泡，第二条起错开一两秒，
     * 像真人边想边发，而不是一口气甩一段。
     */
    private suspend fun insertReply(
        characterId: String,
        text: String,
        firstAt: Long,
        notify: Boolean
    ) {
        val bubbles = splitBubbles(text)
        val now = System.currentTimeMillis()
        bubbles.forEachIndexed { index, bubble ->
            val at = if (firstAt <= now + 1_000L) {
                now + 1_200L + index * 1_800L
            } else {
                firstAt + index * 1_800L
            }
            AppGraph.db.messageDao().insert(
                MessageEntity(
                    characterId = characterId,
                    role = "assistant",
                    content = bubble,
                    createdAt = at,
                    visibleAt = at
                )
            )
        }
        if (notify) {
            val delay = (firstAt - now).coerceAtLeast(60_000L)
            scheduleDelivery(delay, characterId, bubbles.joinToString(" "))
        }
    }

    private fun splitBubbles(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf("……")
        if (trimmed.length <= 20) return listOf(trimmed)

        val pieces = trimmed.split(Regex("(?<=[。！？!?~])|\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val segments = if (pieces.size <= 1) listOf(trimmed) else pieces

        val bubbles = mutableListOf<String>()
        var buffer = ""
        segments.forEach { piece ->
            if (buffer.isEmpty()) {
                buffer = piece
            } else if (buffer.endsWith("……") || buffer.endsWith("...")) {
                // 上一段是没说完的半句，不能在这里断开
                buffer += piece
            } else if ((buffer + piece).length <= 26 && bubbles.size < 2) {
                buffer += piece
            } else {
                bubbles.add(buffer)
                buffer = piece
            }
        }
        if (buffer.isNotEmpty()) bubbles.add(buffer)

        val result = if (bubbles.size > 3) {
            bubbles.take(2) + bubbles.drop(2).joinToString("")
        } else {
            bubbles
        }
        return result.ifEmpty { listOf(trimmed) }
    }

    /** 按性格决定隔多久才回：清冷最慢，粘人最快；心情和关系再微调。 */
    private fun naturalDelayMinutes(
        personality: PersonalityEntity,
        mood: Int,
        intimacy: Int,
        smallTalk: Boolean
    ): Int {
        var value = when {
            personality.independence >= 70 || personality.warmth < 40 -> 5 + Random.nextInt(0, 16)
            personality.clinginess >= 65 -> Random.nextInt(0, 3)
            personality.liveliness >= 65 -> 1 + Random.nextInt(0, 5)
            else -> 1 + Random.nextInt(0, 8)
        }
        if (mood <= 35) value += 5
        if (mood >= 70) value -= 2
        if (intimacy >= 80) value -= 2
        if (smallTalk) value += 2
        return value.coerceIn(0, 20)
    }

    /** 招呼、寒暄、附和：这类话不增加亲密度。带图片的一律不算。 */
    private fun isSmallTalk(text: String, hasImage: Boolean): Boolean {
        if (hasImage) return false
        val normalized = text.trim().lowercase()
            .replace(Regex("[\\s，。！？、,.!?~～…]+"), "")
        if (normalized.isEmpty()) return true
        if (normalized.length == 1) return true

        val plain = setOf(
            "你好", "您好", "在吗", "在不在", "嗨", "哈喽", "hi", "hello", "hey",
            "早", "早安", "早上好", "午安", "晚安", "睡了吗", "睡了没", "吃了没", "吃了吗",
            "在干嘛", "干嘛呢", "好的", "好", "好吧", "嗯", "嗯嗯", "哦", "噢", "ok", "okay",
            "谢谢", "谢了", "不客气", "再见", "拜拜", "bye", "没事", "没什么", "随便",
            "哈哈", "哈哈哈", "hhhh", "笑死"
        )
        if (normalized in plain) return true

        val greetings = listOf(
            "你好", "您好", "在吗", "在不在", "早安", "早上好", "午安", "晚安",
            "哈喽", "嗨", "hi", "hello", "hey", "在干嘛", "干嘛呢"
        )
        return greetings.any {
            normalized.startsWith(it) && normalized.length <= it.length + 2
        }
    }

    private fun parse(raw: String): Decision {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val json = if (start >= 0 && end > start) {
            runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
        } else {
            null
        }

        if (json == null) {
            // 模型没按 JSON 输出：整段当回复，避免整轮丢消息
            return Decision("reply", 0, cleaned, "", null, null, 1)
        }

        val action = json.optString("action", "reply").lowercase().let {
            if (it == "delay" || it == "silent") it else "reply"
        }
        val reply = json.optString("reply", "").trim()
        val caption = json.optString("image_caption", "").trim()
        val mood = json.optInt("mood", -1).takeIf { it in 0..100 }
        val patience = json.optInt("patience", -1).takeIf { it in 0..100 }
        val delta = json.optInt("intimacy_delta", 1).coerceIn(-5, 5)
        val minutes = json.optInt("delay_minutes", 15)
        val search = json.optString("search", "").trim().take(120)

        return if (action == "reply" && reply.isBlank()) {
            Decision("silent", 0, "", caption, mood, patience, delta, search)
        } else {
            Decision(action, minutes, reply, caption, mood, patience, delta, search)
        }
    }

    private fun scheduleDelivery(delayMs: Long, characterId: String, text: String) {
        val context = AppGraph.appContext
        val data = Data.Builder()
            .putString("characterId", characterId)
            .putString("text", text)
            .build()
        val request = OneTimeWorkRequestBuilder<DelayedDeliveryWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(60_000L), TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("deliver_$characterId")
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("deliver_${characterId}_${System.currentTimeMillis()}",
                    ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** 延迟消息到点：前台不打扰，后台推通知（强提醒有效时走强提醒渠道）。 */
class DelayedDeliveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val characterId = inputData.getString("characterId") ?: return Result.success()
        val text = inputData.getString("text") ?: return Result.success()
        if (AppForeground.visible) return Result.success()
        val character = AppGraph.db.characterDao().get(characterId) ?: return Result.success()
        Notifier.post(applicationContext, character, text)
        return Result.success()
    }
}
