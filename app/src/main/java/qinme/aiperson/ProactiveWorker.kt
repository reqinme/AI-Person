package qinme.aiperson

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 主动消息：她可以想念、分享、也可以突然找你求助。
 * 只在开关打开后运行；22:00-08:00 静默；每天有上限。
 * 发不发、隔多久发，按她的底色和关系算，不设每日保底。
 */
class ProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private data class Candidate(
        val character: CharacterEntity,
        val relationship: RelationshipEntity,
        val personality: PersonalityEntity,
        val shown: PersonalityEntity,
        val chance: Float
    )

    override suspend fun doWork(): Result {
        // 后台冷启动时 Application.onCreate 可能尚未跑到，这里再做一次幂等初始化
        AppGraph.init(applicationContext)
        BehaviorSettings.init(applicationContext)
        if (!BehaviorSettings.proactiveEnabled) return Result.success()
        if (Timing.inQuietHours(System.currentTimeMillis())) return Result.success()

        val manager = androidx.core.app.NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return Result.success()
        if (BehaviorSettings.proactiveCountToday() >= BehaviorSettings.proactiveDailyCap) {
            return Result.success()
        }

        val db = AppGraph.db
        val now = System.currentTimeMillis()
        val idleLimit = TimeUnit.HOURS.toMillis(BehaviorSettings.proactiveIdleHours.toLong())

        val candidates = db.characterDao().all().mapNotNull { character ->
            val relationship = db.relationshipDao().get(character.id) ?: return@mapNotNull null
            if (relationship.intimacy < BehaviorSettings.PROACTIVE_MIN_STAGE) return@mapNotNull null
            if (now - relationship.lastChatAt < idleLimit) return@mapNotNull null
            if (!AppGraph.api.configFor(character).apiKey.isNotBlank()) return@mapNotNull null

            val personality = db.personalityDao().get(character.id)
                ?: PersonalityEntity(characterId = character.id)
            val shown = effectivePersonality(personality, relationship.intimacy)

            // 越主动、越粘、关系越近，间隔越短；清冷的人可能几天才想找他一次
            val intervalHours = (30f -
                shown.initiative / 100f * 14f -
                shown.clinginess / 100f * 8f -
                relationship.intimacy / 100f * 6f).coerceIn(4f, 36f)
            val lastAt = maxOf(
                BehaviorSettings.lastProactiveAt(character.id),
                relationship.lastChatAt
            )
            if (now - lastAt < intervalHours * 3_600_000L) return@mapNotNull null

            val chance = 0.10f +
                shown.initiative / 100f * 0.30f +
                shown.clinginess / 100f * 0.16f +
                relationship.intimacy / 100f * 0.14f
            Candidate(character, relationship, personality, shown, chance)
        }.sortedByDescending {
            it.shown.initiative * 0.5f + it.shown.clinginess * 0.3f + it.relationship.intimacy * 0.2f
        }

        val target = candidates.firstOrNull() ?: return Result.success()
        if (Random.nextFloat() > target.chance) return Result.success()

        val character = target.character
        val relationship = target.relationship
        val mood = db.moodDao().get(character.id)
        val memories = db.memoryDao().recall(character.id, BehaviorSettings.RECALL_LIMIT)
        if (memories.isNotEmpty()) {
            db.memoryDao().touchRecalled(memories.map { it.id }, now)
        }

        val kind = pickKind(target.shown, character.id)
        val knownDays = ((now - character.createdAt) / TimeUnit.DAYS.toMillis(1)).toInt() + 1
        val totalMessages = db.messageDao().countDialogue(character.id)
        val system = PromptBuilder.proactive(
            character,
            relationship,
            target.personality,
            mood,
            memories,
            kind,
            knownDays,
            totalMessages
        )

        val text = runCatching {
            Llm.complete(
                character,
                system,
                listOf(
                    MessageEntity(
                        characterId = character.id,
                        role = "user",
                        content = "（还没有新消息）",
                        createdAt = now
                    )
                ),
                temperature = 0.9
            ).trim()
        }.getOrNull()

        if (text.isNullOrBlank()) return Result.success()

        db.messageDao().insert(
            MessageEntity(
                characterId = character.id,
                role = "assistant",
                content = text,
                createdAt = now,
                proactive = true
            )
        )
        db.relationshipDao().upsert(relationship.copy(lastChatAt = now))
        BehaviorSettings.markProactiveSent()
        BehaviorSettings.setLastProactiveAt(character.id, now)
        if (kind == KIND_HELP) BehaviorSettings.markHelpSent()
        BehaviorSettings.setLastKind(character.id, kind)

        // 你正在用 App 时，消息直接出现在聊天里，不再弹系统通知
        if (!AppForeground.visible) {
            Notifier.post(applicationContext, character, text)
        }
        return Result.success()
    }

    /** 想念/分享按她的性格加权，求助只在今天还没用过时出现；不连续重复上一轮的意图。 */
    private fun pickKind(personality: PersonalityEntity, characterId: String): String {
        val last = BehaviorSettings.lastKind(characterId)
        val pool = buildList {
            repeat((10 + personality.clinginess).coerceIn(5, 40)) { add(KIND_MISS) }
            repeat((10 + personality.liveliness).coerceIn(5, 40)) { add(KIND_SHARE) }
            if (BehaviorSettings.canSendHelpToday()) {
                repeat((5 + personality.initiative / 2).coerceIn(2, 30)) { add(KIND_HELP) }
            }
        }.filter { it != last }
        return pool.randomOrNull() ?: KIND_SHARE
    }

    companion object {
        const val KIND_MISS = "想念"
        const val KIND_SHARE = "分享日常"
        const val KIND_HELP = "求助"
    }
}

object ProactiveScheduler {
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "aip_proactive",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
