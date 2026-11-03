package qinme.aiperson

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 主动消息：她可以想念、分享、也可以突然找你求助。
 * 只在开关打开后运行；22:00-08:00 静默；每天有上限；求助类每天最多一次。
 */
class ProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
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
            character to relationship
        }.sortedByDescending { it.second.intimacy }

        val target = candidates.firstOrNull() ?: return Result.success()
        val (character, relationship) = target
        if (!AppGraph.api.configFor(character).apiKey.isNotBlank()) return Result.success()

        val mood = db.moodDao().get(character.id)
        val personality = db.personalityDao().get(character.id)
        val memories = db.memoryDao().recall(character.id, BehaviorSettings.RECALL_LIMIT)

        val kind = pickKind(character.id)
        val knownDays = ((now - character.createdAt) / TimeUnit.DAYS.toMillis(1)).toInt() + 1
        val totalMessages = db.messageDao().countDialogue(character.id)
        val system = PromptBuilder.proactive(
            character,
            relationship,
            personality,
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
        if (kind == KIND_HELP) BehaviorSettings.markHelpSent()
        BehaviorSettings.setLastKind(character.id, kind)

        Notifier.post(applicationContext, character, text)
        return Result.success()
    }

    private fun pickKind(characterId: String): String {
        val last = BehaviorSettings.lastKind(characterId)
        val helpAvailable = BehaviorSettings.canSendHelpToday()
        val pool = buildList {
            add(KIND_MISS)
            add(KIND_SHARE)
            if (helpAvailable) add(KIND_HELP)
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
