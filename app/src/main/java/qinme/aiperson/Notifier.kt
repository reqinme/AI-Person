package qinme.aiperson

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 记录 App 是否在前台：前台时延迟消息直接冒出来，不发系统通知。 */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}

object Notifier {
    const val CHANNEL_NORMAL = "aip_normal"
    const val CHANNEL_STRONG = "aip_strong"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val normal = NotificationChannel(
            CHANNEL_NORMAL,
            "她发来的消息",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "主动消息和延迟到达的消息" }
        manager.createNotificationChannel(normal)

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val strong = NotificationChannel(
            CHANNEL_STRONG,
            "强提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "开启强提醒的这段时间内，她发消息会响一声短铃"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 120, 200)
            setSound(sound, null)
            enableLights(true)
        }
        manager.createNotificationChannel(strong)
    }

    fun strongAlertActive(character: CharacterEntity): Boolean =
        character.strongAlertAt > System.currentTimeMillis()

    fun post(context: Context, character: CharacterEntity, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val strong = strongAlertActive(character)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("characterId", character.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            1000 + character.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            context,
            if (strong) CHANNEL_STRONG else CHANNEL_NORMAL
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(character.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (strong) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        ImageStore.bitmap(context, character.avatarPath)?.let { builder.setLargeIcon(it) }

        runCatching {
            manager.notify(2000 + character.id.hashCode(), builder.build())
        }
    }

    /** 前台时的短铃。 */
    fun playShortTone(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        }
    }
}
