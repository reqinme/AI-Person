package qinme.aiperson

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

/**
 * 成长管线：每 N 轮压缩一次记忆、演化人格；亲密度已在每轮对话中判定。
 * 另外负责记忆衰减（先降级成模糊，再删除）。
 */
object GrowthEngine {

    private const val EVAL_PROMPT = """你是一个记忆整理器。阅读下面这段最近的对话，输出长期记忆和人格变化。

    只输出一个 JSON 对象，不要 markdown、不要注释、不要多余文字：
    {"memories":[{"content":"关于用户或这段关系的一句事实，第三人称，20 字以内","importance":1,"tag":""}],"personality":{"warmth":0,"liveliness":0,"initiative":0,"independence":0,"clinginess":0},"persona":{"change":"","reason":"","nickname":""}}

    规则：
    1. memories 只写值得长期记住的事（偏好、经历、重要的人、承诺、底线），最多 4 条；没有就给空数组。
    2. importance 取 1 到 5，越重要越高；寒暄、天气、一次性小事不要记。
    3. personality 的每项是 -2 到 2 的变化量，代表这段对话把人设往哪个方向推了一点点；没有明显变化就给 0。
    4. 不要复述整段对话，不要写"用户说了…"这种流水账。
    5. tag 默认为空字符串。如果这段对话里出现了她反感、讨厌的事，或者用户明确说过讨厌什么，
       把这条写成软印象，tag 填 "dislike"，内容写成「用户讨厌…」或「她不喜欢…」。
       不要照抄角色设定里的硬底线，只记录这段对话里自然浮现的、具体的好恶。
    6. 变化必须延续她的底色，不能突然换成另一种性格。比如底色偏冷的人，最多只是对这个人稍微软了一点。
    7. persona.change：如果这段相处真的让她有了一点点变化，用一句第三人称写"她变成什么样"，
       不要写数字、不要写成总结报告；没有变化就留空字符串。
    8. persona.reason 用一句很短的话说明为什么会变，比如"被认真回应过"。
    9. persona.nickname：只有关系已经亲近、她对用户的称呼自然变了时才填新称呼（比如"阿哲"），
       否则留空。不要为了显得亲近而编造。"""

    suspend fun maybeGrow(characterId: String) = withContext(Dispatchers.IO) {
        val db = AppGraph.db
        val needed = (BehaviorSettings.compressionInterval * 2).coerceAtLeast(4)
        val lastId = BehaviorSettings.lastEvalId(characterId)
        if (db.messageDao().countAfter(characterId, lastId) < needed) return@withContext

        val character = db.characterDao().get(characterId) ?: return@withContext
        val relationship = db.relationshipDao().get(characterId)
        val personality = db.personalityDao().get(characterId)
        val mood = db.moodDao().get(characterId)

        val transcript = db.messageDao().recent(characterId, needed * 2)
            .filter {
                // 只喂给模型上次整理之后的新消息，避免同一段对话反复影响人格
                it.id > lastId &&
                    (it.role == "user" || it.role == "assistant") &&
                    (it.visibleAt == 0L || it.visibleAt <= System.currentTimeMillis())
            }
            .reversed()
            .joinToString("\n") { message ->
                val who = if (message.role == "user") "用户" else character.name
                val pic = if (message.imageCaption.isNotBlank()) "（图片：${message.imageCaption}）" else ""
                "$who：${message.content}$pic"
            }
        val latest = db.messageDao().latestId(characterId) ?: return@withContext

        if (transcript.isBlank()) {
            BehaviorSettings.setLastEvalId(characterId, latest)
            return@withContext
        }

        val head = buildString {
            appendLine("角色：${character.name}")
            appendLine("人设：${character.persona}")
            appendLine("底色：${personality?.temperament ?: "自定义"}")
            appendLine("关系阶段：${intimacyStage(relationship?.intimacy ?: 0)}")
            appendLine("当前人格：${personality?.let { "温柔${it.warmth} 活泼${it.liveliness} 主动${it.initiative} 独立${it.independence} 粘人${it.clinginess}" } ?: "未知"}")
            appendLine("底色值：${personality?.let { "温柔${it.baseWarmth} 活泼${it.baseLiveliness} 主动${it.baseInitiative} 独立${it.baseIndependence} 粘人${it.baseClinginess}" } ?: "未知"}")
            if (!personality?.personaChanges.isNullOrBlank()) {
                appendLine("这些年她变成：${personality?.personaChanges?.replace("\n", "；")}")
            }
            if (character.nickname.isNotBlank()) appendLine("她现在叫他：${character.nickname}")
            appendLine("当前心情：${mood?.mood ?: 55}/100")
            appendLine()
            appendLine("最近对话：")
            appendLine(transcript.take(3000))
        }

        val raw = try {
            Llm.complete(
                character,
                EVAL_PROMPT + "\n\n" + head,
                listOf(
                    MessageEntity(
                        characterId = characterId,
                        role = "user",
                        content = "请整理上面的对话，输出 JSON。",
                        createdAt = System.currentTimeMillis()
                    )
                ),
                temperature = 0.2
            )
        } catch (e: Exception) {
            // 网络失败不推进游标，下轮还能补上
            return@withContext
        }

        val json = extractJson(raw)
        if (json == null) {
            BehaviorSettings.setLastEvalId(characterId, latest)
            return@withContext
        }

        writeMemories(characterId, json)
        evolvePersonality(characterId, character, personality, json)
        BehaviorSettings.setLastEvalId(characterId, latest)
        MemoryDecay.run(characterId)
    }

    private suspend fun writeMemories(characterId: String, json: JSONObject) {
        val dao = AppGraph.db.memoryDao()
        val array = json.optJSONArray("memories") ?: return
        val existing = dao.list(characterId)
        val now = System.currentTimeMillis()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val content = item.optString("content", "").trim()
            if (content.length < 2) continue
            val importance = item.optInt("importance", 3).coerceIn(1, 5)
            val tags = if (item.optString("tag", "").trim() == "dislike") "dislike" else ""

            val matched = existing.firstOrNull { sameMemory(it.content, content) }
            if (matched != null) {
                // 重复的记忆加深印象，而不是丢弃
                val boosted = minOf(5, maxOf(matched.importance, importance) + 1)
                dao.reinforce(matched.id, boosted, boosted.toFloat(), now)
            } else if (dao.countSame(characterId, content) == 0) {
                dao.insert(
                    MemoryEntity(
                        characterId = characterId,
                        content = content,
                        importance = importance,
                        tags = tags,
                        weight = importance.toFloat(),
                        createdAt = now,
                        lastRecalledAt = now
                    )
                )
            }
        }
    }

    private suspend fun evolvePersonality(
        characterId: String,
        character: CharacterEntity,
        personality: PersonalityEntity?,
        json: JSONObject
    ) {
        val delta = json.optJSONObject("personality") ?: JSONObject()
        val current = personality ?: PersonalityEntity(characterId = characterId)
        val based = growBase(characterId, current, delta)
        val updated = based.copy(
            warmth = drift(based.warmth, based.prefWarmth, based.baseWarmth, delta.optInt("warmth", 0)),
            liveliness = drift(based.liveliness, based.prefLiveliness, based.baseLiveliness, delta.optInt("liveliness", 0)),
            initiative = drift(based.initiative, based.prefInitiative, based.baseInitiative, delta.optInt("initiative", 0)),
            independence = drift(based.independence, based.prefIndependence, based.baseIndependence, delta.optInt("independence", 0)),
            clinginess = drift(based.clinginess, based.prefClinginess, based.baseClinginess, delta.optInt("clinginess", 0))
        )
        AppGraph.db.personalityDao().upsert(updated)
        evolvePersona(characterId, character, updated, json)
    }

    /** 当前值只在小范围里随对话晃动：模型方向 + 往偏好 8% 的拉力 + 一点噪声。 */
    private fun drift(current: Int, pref: Int, base: Int, delta: Int): Int {
        val pull = Math.round((pref - current) * 0.08f)
        val noise = Random.nextInt(-1, 2)
        val moved = current + delta.coerceIn(-2, 2) + pull + noise
        val low = maxOf(0, base - 15)
        val high = minOf(100, base + 15)
        return moved.coerceIn(low, high)
    }

    /**
     * 长期底色：单次最多 ±2，30 天内同向出现至少 3 次才动一次，
     * 每个维度滑一年最多 ±10，预置原型还要求距离中心不超过 20。
     */
    private suspend fun growBase(
        characterId: String,
        personality: PersonalityEntity,
        delta: JSONObject
    ): PersonalityEntity {
        val now = System.currentTimeMillis()
        val state = parseDriftState(BehaviorSettings.driftState(characterId))
        if (state.yearStart <= 0L || now - state.yearStart > TimeUnit.DAYS.toMillis(365)) {
            state.yearStart = now
            state.applied.clear()
        }
        if (state.windowStart <= 0L) state.windowStart = now

        var result = personality
        DIMS.forEach { (short, field) ->
            val value = delta.optInt(field, 0).coerceIn(-2, 2)
            if (value == 0) return@forEach
            val dir = if (value > 0) 1 else -1
            val sameWindow = now - state.windowStart <= TimeUnit.DAYS.toMillis(30)
            val count = if (state.dirs[short] == dir && sameWindow) {
                (state.counts[short] ?: 0) + 1
            } else {
                1
            }
            state.dirs[short] = dir
            state.counts[short] = count

            if (count >= 3) {
                val applied = state.applied[short] ?: 0
                if (applied + dir * 2 in -10..10) {
                    val currentBase = baseOf(result, field)
                    val center = Temperaments.centerOf(result, field)
                    var next = (currentBase + dir * 2).coerceIn(0, 100)
                    if (center != null) {
                        next = next.coerceIn(
                            (center - 20).coerceAtLeast(0),
                            (center + 20).coerceAtMost(100)
                        )
                    }
                    if (next != currentBase) {
                        result = withBase(result, field, next)
                        state.applied[short] = applied + (next - currentBase)
                    }
                }
                state.counts[short] = 0
            }
        }
        if (now - state.windowStart > TimeUnit.DAYS.toMillis(30)) state.windowStart = now
        BehaviorSettings.setDriftState(characterId, saveDriftState(state))
        return result
    }

    /**
     * 人设变化：至少 7 天、至少 40 条新消息、30 天内最多 2 条。
     * 变化本身由模型写成一句叙述，这里只做闸门和落库。
     */
    private suspend fun evolvePersona(
        characterId: String,
        character: CharacterEntity,
        personality: PersonalityEntity,
        json: JSONObject
    ) {
        val persona = json.optJSONObject("persona") ?: return
        val change = persona.optString("change", "").trim().take(40)
        val reason = persona.optString("reason", "").trim().take(24)
        val nickname = persona.optString("nickname", "").trim().take(8)
        if (change.length < 4 && nickname.isBlank()) return

        val db = AppGraph.db
        val now = System.currentTimeMillis()
        val lastAt = personality.lastPersonaChangeAt.takeIf { it > 0 } ?: character.createdAt
        if (now - lastAt < TimeUnit.DAYS.toMillis(7)) return
        if (db.messageDao().countDialogueSince(characterId, lastAt) < 40) return
        if (db.personaHistoryDao().countSince(characterId, now - TimeUnit.DAYS.toMillis(30)) >= 2) return

        var updated = personality
        val freshLines = mutableListOf<String>()

        if (change.length >= 4 && change.none { it.isDigit() }) {
            db.personaHistoryDao().insert(
                PersonaChangeEntity(
                    characterId = characterId,
                    change = change,
                    reason = reason,
                    createdAt = now
                )
            )
            freshLines += change
        }

        val intimacy = db.relationshipDao().get(characterId)?.intimacy ?: 0
        if (nickname.isNotBlank() && intimacy >= 50 && nickname != character.nickname) {
            db.characterDao().upsert(character.copy(nickname = nickname))
            db.personaHistoryDao().insert(
                PersonaChangeEntity(
                    characterId = characterId,
                    change = "她开始叫他「$nickname」",
                    reason = "关系近了，称呼自然就变了",
                    createdAt = now
                )
            )
        }

        if (freshLines.isNotEmpty()) {
            val kept = (freshLines + personality.personaChanges.lines())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)
            updated = updated.copy(
                personaChanges = kept.joinToString("\n"),
                lastPersonaChangeAt = now
            )
        }
        db.personalityDao().upsert(updated)
    }

    private fun baseOf(p: PersonalityEntity, field: String): Int = when (field) {
        "warmth" -> p.baseWarmth
        "liveliness" -> p.baseLiveliness
        "initiative" -> p.baseInitiative
        "independence" -> p.baseIndependence
        else -> p.baseClinginess
    }

    private fun withBase(p: PersonalityEntity, field: String, value: Int): PersonalityEntity = when (field) {
        "warmth" -> p.copy(baseWarmth = value)
        "liveliness" -> p.copy(baseLiveliness = value)
        "initiative" -> p.copy(baseInitiative = value)
        "independence" -> p.copy(baseIndependence = value)
        else -> p.copy(baseClinginess = value)
    }

    private data class DriftState(
        var windowStart: Long = 0L,
        var yearStart: Long = 0L,
        val dirs: MutableMap<String, Int> = mutableMapOf(),
        val counts: MutableMap<String, Int> = mutableMapOf(),
        val applied: MutableMap<String, Int> = mutableMapOf()
    )

    private fun parseDriftState(raw: String): DriftState {
        if (raw.isBlank()) return DriftState()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return DriftState()
        val state = DriftState(
            windowStart = json.optLong("ws", 0L),
            yearStart = json.optLong("ys", 0L)
        )
        listOf("dirs", "counts", "applied").forEach { key ->
            val obj = json.optJSONObject(key) ?: return@forEach
            val target = when (key) {
                "dirs" -> state.dirs
                "counts" -> state.counts
                else -> state.applied
            }
            obj.keys().forEach { name -> target[name] = obj.optInt(name, 0) }
        }
        return state
    }

    private fun saveDriftState(state: DriftState): String = JSONObject()
        .put("ws", state.windowStart)
        .put("ys", state.yearStart)
        .put("dirs", JSONObject().apply { state.dirs.forEach { (k, v) -> put(k, v) } })
        .put("counts", JSONObject().apply { state.counts.forEach { (k, v) -> put(k, v) } })
        .put("applied", JSONObject().apply { state.applied.forEach { (k, v) -> put(k, v) } })
        .toString()

    private val DIMS = listOf(
        "w" to "warmth",
        "l" to "liveliness",
        "i" to "initiative",
        "ind" to "independence",
        "c" to "clinginess"
    )

    private fun sameMemory(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        return shorter.length >= 4 && longer.contains(shorter)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s，。！？,.!?、：:；;\"'（）()【】\\[\\]]"), "")

    fun extractJson(raw: String): JSONObject? {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }
}

/** 记忆衰减：半衰期 14 天，低于 2 变模糊，低于 0.5 且 30 天未回忆才删除；钉住的不衰减。 */
object MemoryDecay {
    suspend fun run(characterId: String? = null) = withContext(Dispatchers.IO) {
        val db = AppGraph.db
        val characters = if (characterId != null) {
            listOfNotNull(db.characterDao().get(characterId))
        } else {
            db.characterDao().all()
        }
        val now = System.currentTimeMillis()

        characters.forEach { character ->
            val dao = db.memoryDao()
            val memories = dao.list(character.id)
            memories.forEach { memory ->
                if (memory.pinned) {
                    dao.updateWeight(memory.id, maxOf(memory.weight, memory.importance.toFloat()), "ACTIVE")
                    return@forEach
                }
                val days = (now - memory.lastRecalledAt).toFloat() / TimeUnit.DAYS.toMillis(1).toFloat()
                val weight = memory.importance.toFloat() *
                    0.5f.pow(days / BehaviorSettings.HALF_LIFE_DAYS.toFloat())
                val tier = if (weight >= BehaviorSettings.DEGRADE_THRESHOLD) "ACTIVE" else "FADED"
                dao.updateWeight(memory.id, weight, tier)
            }
            dao.deleteStale(
                BehaviorSettings.DELETE_THRESHOLD,
                now - TimeUnit.DAYS.toMillis(BehaviorSettings.DELETE_AFTER_DAYS)
            )

            // 长期不聊天，关系会慢慢变淡（同一天只退一次）
            val relationship = db.relationshipDao().get(character.id) ?: return@forEach
            // 还没开始聊过天的角色不做“从 1970 年算起”的惩罚
            if (relationship.lastChatAt <= 0L) return@forEach
            val idleDays = ((now - relationship.lastChatAt) / TimeUnit.DAYS.toMillis(1)).toInt()
            if (idleDays <= 0) {
                BehaviorSettings.resetIdlePenalty(character.id)
                return@forEach
            }
            val penalty = idleDays - 7
            val applied = BehaviorSettings.idlePenaltyApplied(character.id)
            if (penalty > applied) {
                val subtract = penalty - applied
                val raw = BehaviorSettings.intimacyRaw(character.id) ?: relationship.intimacy
                val nextRaw = (raw - subtract).coerceAtLeast(0)
                BehaviorSettings.setIntimacyRaw(character.id, nextRaw)
                db.relationshipDao().upsert(
                    relationship.copy(intimacy = nextRaw.coerceAtMost(100))
                )
                BehaviorSettings.setIdlePenalty(character.id, penalty)
            }
        }
    }
}
