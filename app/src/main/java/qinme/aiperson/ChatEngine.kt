package qinme.aiperson

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 一轮完整的对话：写入用户消息 → 调模型（带回不回/延迟/心情/亲密度）→ 落库。
 */
object ChatEngine {

    data class Result(val ok: Boolean, val error: String? = null)

    private data class Decision(
        val action: String,
        val delayMinutes: Int,
        val reply: String,
        val caption: String,
        val mood: Int?,
        val patience: Int?,
        val intimacyDelta: Int
    )

    suspend fun send(characterId: String, text: String, imageName: String = ""): Result =
        withContext(Dispatchers.IO) {
            val db = AppGraph.db
            val character = db.characterDao().get(characterId)
                ?: return@withContext Result(false, "角色不存在")

            val relationship = db.relationshipDao().get(characterId)
                ?: RelationshipEntity(characterId = characterId).also { db.relationshipDao().upsert(it) }
            val personality = db.personalityDao().get(characterId)
                ?: PersonalityEntity(characterId = characterId).also { db.personalityDao().upsert(it) }
            val mood = db.moodDao().get(characterId)
                ?: MoodEntity(characterId = characterId).also { db.moodDao().upsert(it) }

            val now = System.currentTimeMillis()
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

            val history = db.messageDao().recent(characterId, 24)
                .filter {
                    (it.role == "user" || it.role == "assistant") &&
                        (it.visibleAt == 0L || it.visibleAt <= now)
                }
                .reversed()
            val memories = db.memoryDao().recall(characterId, BehaviorSettings.RECALL_LIMIT)
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
                return@withContext Result(false, e.message ?: "连接失败")
            }

            val decision = parse(raw)

            // 图片描述记在用户那条消息上，后续记忆压缩会用到
            if (imageName.isNotBlank() && decision.caption.isNotBlank()) {
                db.messageDao().setCaption(messageId, decision.caption.take(120))
            }

            // 心情
            val newMood = mood.copy(
                mood = (decision.mood ?: mood.mood).coerceIn(0, 100),
                patience = (decision.patience ?: mood.patience).coerceIn(0, 100),
                updatedAt = now
            )
            db.moodDao().upsert(newMood)

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
                    db.messageDao().insert(
                        MessageEntity(
                            characterId = characterId,
                            role = "assistant",
                            content = decision.reply,
                            createdAt = target,
                            visibleAt = target
                        )
                    )
                    scheduleDelivery(target - now, characterId, decision.reply)
                }

                else -> {
                    val reply = decision.reply.ifBlank { "……" }
                    db.messageDao().insert(
                        MessageEntity(
                            characterId = characterId,
                            role = "assistant",
                            content = reply,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            runCatching { GrowthEngine.maybeGrow(characterId) }
            Result(true)
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

        return if (action == "reply" && reply.isBlank()) {
            Decision("silent", 0, "", caption, mood, patience, delta)
        } else {
            Decision(action, minutes, reply, caption, mood, patience, delta)
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
        val characterId = inputData.getString("characterId") ?: return Result.success()
        val text = inputData.getString("text") ?: return Result.success()
        if (AppForeground.visible) return Result.success()
        val character = AppGraph.db.characterDao().get(characterId) ?: return Result.success()
        Notifier.post(applicationContext, character, text)
        return Result.success()
    }
}
