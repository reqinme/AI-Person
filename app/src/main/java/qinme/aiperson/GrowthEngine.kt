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
    {"memories":[{"content":"关于用户或这段关系的一句事实，第三人称，20 字以内","importance":1,"tag":""}],"personality":{"warmth":0,"liveliness":0,"initiative":0,"independence":0,"clinginess":0}}

    规则：
    1. memories 只写值得长期记住的事（偏好、经历、重要的人、承诺、底线），最多 4 条；没有就给空数组。
    2. importance 取 1 到 5，越重要越高；寒暄、天气、一次性小事不要记。
    3. personality 的每项是 -2 到 2 的变化量，代表这段对话把人设往哪个方向推了一点点；没有明显变化就给 0。
    4. 不要复述整段对话，不要写"用户说了…"这种流水账。
    5. tag 默认为空字符串。如果这段对话里出现了她反感、讨厌的事，或者用户明确说过讨厌什么，
       把这条写成软印象，tag 填 "dislike"，内容写成「用户讨厌…」或「她不喜欢…」。
       不要照抄角色设定里的硬底线，只记录这段对话里自然浮现的、具体的好恶。"""

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
            appendLine("关系阶段：${intimacyStage(relationship?.intimacy ?: 0)}")
            appendLine("当前人格：${personality?.let { "温柔${it.warmth} 活泼${it.liveliness} 主动${it.initiative} 独立${it.independence} 粘人${it.clinginess}" } ?: "未知"}")
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
        evolvePersonality(characterId, personality, json)
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
        personality: PersonalityEntity?,
        json: JSONObject
    ) {
        val delta = json.optJSONObject("personality") ?: return
        val current = personality ?: PersonalityEntity(characterId = characterId)
        val updated = current.copy(
            warmth = drift(current.warmth, current.prefWarmth, delta.optInt("warmth", 0)),
            liveliness = drift(current.liveliness, current.prefLiveliness, delta.optInt("liveliness", 0)),
            initiative = drift(current.initiative, current.prefInitiative, delta.optInt("initiative", 0)),
            independence = drift(current.independence, current.prefIndependence, delta.optInt("independence", 0)),
            clinginess = drift(current.clinginess, current.prefClinginess, delta.optInt("clinginess", 0))
        )
        AppGraph.db.personalityDao().upsert(updated)
    }

    /** 模型给的方向 + 向偏好 15% 的拉力 + 一点随机噪声，偏好只是软引导。 */
    private fun drift(current: Int, pref: Int, delta: Int): Int {
        val pull = Math.round((pref - current) * 0.15f)
        val noise = Random.nextInt(-1, 2)
        return (current + delta.coerceIn(-2, 2) + pull + noise).coerceIn(0, 100)
    }

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
