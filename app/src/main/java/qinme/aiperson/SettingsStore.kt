package qinme.aiperson

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 行为设置：压缩间隔、主动消息等。默认值即产品默认值。 */
object BehaviorSettings {
    private lateinit var prefs: SharedPreferences

    // 记忆与成长
    var compressionInterval by mutableStateOf(8)
        private set

    // 主动消息
    var proactiveEnabled by mutableStateOf(false)
        private set
    var proactiveIdleHours by mutableStateOf(12)
        private set
    var proactiveDailyCap by mutableStateOf(1)
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("behavior_prefs", Context.MODE_PRIVATE)
        compressionInterval = prefs.getInt("compression_interval", 8)
        proactiveEnabled = prefs.getBoolean("proactive_enabled", false)
        proactiveIdleHours = prefs.getInt("proactive_idle_hours", 12)
        proactiveDailyCap = prefs.getInt("proactive_daily_cap", 1)
    }

    fun updateCompressionInterval(value: Int) {
        compressionInterval = value.coerceIn(4, 20)
        prefs.edit().putInt("compression_interval", compressionInterval).apply()
    }

    fun updateProactiveEnabled(value: Boolean) {
        proactiveEnabled = value
        prefs.edit().putBoolean("proactive_enabled", value).apply()
    }

    fun updateProactiveIdleHours(value: Int) {
        proactiveIdleHours = value.coerceIn(6, 24)
        prefs.edit().putInt("proactive_idle_hours", proactiveIdleHours).apply()
    }

    fun updateProactiveDailyCap(value: Int) {
        proactiveDailyCap = value.coerceIn(1, 3)
        prefs.edit().putInt("proactive_daily_cap", proactiveDailyCap).apply()
    }

    // 每个角色上次做记忆压缩时的消息 id
    fun lastEvalId(characterId: String): Long =
        prefs.getLong("last_eval_$characterId", 0L)

    fun setLastEvalId(characterId: String, id: Long) {
        prefs.edit().putLong("last_eval_$characterId", id).apply()
    }

    // 长期不聊的亲密度的已扣分数，避免每次启动重复扣
    fun idlePenaltyApplied(characterId: String): Int =
        prefs.getInt("idle_applied_$characterId", 0)

    fun setIdlePenalty(characterId: String, value: Int) {
        prefs.edit().putInt("idle_applied_$characterId", value).apply()
    }

    fun resetIdlePenalty(characterId: String) {
        prefs.edit().remove("idle_applied_$characterId").apply()
    }

    /**
     * 亲密度内部累计值。显示永远封顶 100，但内部继续加减，
     * 这样刚到 100 时踩一两次线不会立刻掉下来。
     */
    fun intimacyRaw(characterId: String): Int? =
        prefs.getInt("intimacy_raw_$characterId", -1).takeIf { it >= 0 }

    fun setIntimacyRaw(characterId: String, value: Int) {
        prefs.edit().putInt("intimacy_raw_$characterId", value.coerceAtLeast(0)).apply()
    }

    fun resetIntimacyRaw(characterId: String) {
        prefs.edit().remove("intimacy_raw_$characterId").apply()
    }

    // 主动消息的每日计数
    fun proactiveCountToday(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return if (prefs.getString("proactive_date", "") == today) {
            prefs.getInt("proactive_count", 0)
        } else {
            0
        }
    }

    fun markProactiveSent() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val count = proactiveCountToday() + 1
        prefs.edit().putString("proactive_date", today).putInt("proactive_count", count).apply()
    }

    /** 求助类每天最多 1 次。 */
    fun canSendHelpToday(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return prefs.getString("help_date", "") != today
    }

    fun markHelpSent() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.edit().putString("help_date", today).apply()
    }

    /** 最近一次主动消息的意图，避免连续重复。 */
    fun lastKind(characterId: String): String = prefs.getString("last_kind_$characterId", "").orEmpty()

    fun setLastKind(characterId: String, kind: String) {
        prefs.edit().putString("last_kind_$characterId", kind).apply()
    }

    /** 上次主动找他的时间，用来按性格拉开间隔，而不是每天固定发一条。 */
    fun lastProactiveAt(characterId: String): Long =
        prefs.getLong("last_proactive_$characterId", 0L)

    fun setLastProactiveAt(characterId: String, at: Long) {
        prefs.edit().putLong("last_proactive_$characterId", at).apply()
    }

    /**
     * 心情惯性：冲突后还剩几轮不恢复。
     * 大于 0 时，这一轮心情只能往下走，不能立刻回升。
     */
    fun moodHold(characterId: String): Int = prefs.getInt("mood_hold_$characterId", 0)

    fun setMoodHold(characterId: String, rounds: Int) {
        prefs.edit().putInt("mood_hold_$characterId", rounds.coerceAtLeast(0)).apply()
    }

    /** 底色慢漂移的累计证据（JSON 字符串，由 GrowthEngine 解释）。 */
    fun driftState(characterId: String): String =
        prefs.getString("drift_state_$characterId", "").orEmpty()

    fun setDriftState(characterId: String, value: String) {
        prefs.edit().putString("drift_state_$characterId", value).apply()
    }

    fun resetMoodHold(characterId: String) {
        prefs.edit().remove("mood_hold_$characterId").remove("drift_state_$characterId")
            .remove("last_proactive_$characterId").apply()
    }

    // 记忆衰减参数（固定默认值）
    const val HALF_LIFE_DAYS = 14.0
    const val DEGRADE_THRESHOLD = 2.0f
    const val DELETE_THRESHOLD = 0.5f
    const val DELETE_AFTER_DAYS = 30L
    const val RECALL_LIMIT = 6
    const val QUIET_START_HOUR = 22
    const val QUIET_END_HOUR = 8
    const val PROACTIVE_MIN_STAGE = 20
}

/**
 * 时间规则：她什么时候睡、什么时候醒，按性格和当晚的状态算。
 * 睡着时消息不会被标成已读，回复也压到醒来。
 */
object Timing {
    private const val MAX_DELAY_MINUTES = 360

    /** 上床时间，可能超过 24 点（25 表示凌晨 1 点）。 */
    fun bedHour(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Int {
        if (routine == Routine.NIGHT) return Routine.baseBed(now, routine).toInt() % 24
        val late = ((shown.liveliness - 50) / 50f).coerceIn(-1f, 1f)
        val independent = ((shown.independence - 50) / 50f).coerceIn(-1f, 1f)
        val wild = if (Routine.staysUpLate(now, characterId, shown, routine)) 1.5f else 0f
        val base = Routine.baseBed(now, routine) + late * 1.2f + independent * 0.6f + wild
        return Math.round(base.coerceIn(21.5f, 26f)) % 24
    }

    /** 醒来时间；熬夜的第二天会晚一点。 */
    fun wakeHour(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Int {
        if (routine == Routine.NIGHT) return Routine.baseWake(now, routine)
        val late = ((shown.liveliness - 50) / 50f).coerceIn(-1f, 1f)
        val wild = if (Routine.staysUpLate(now, characterId, shown, routine)) 1f else 0f
        return Math.round((Routine.baseWake(now, routine).toFloat() + late * 1.2f + wild).coerceIn(6f, 10f))
    }

    fun isAsleep(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Boolean {
        val bed = bedHour(now, characterId, shown, routine)
        val wake = wakeHour(now, characterId, shown, routine)
        val hour = java.util.Calendar.getInstance()
            .apply { timeInMillis = now }
            .get(java.util.Calendar.HOUR_OF_DAY)
        return if (bed < wake) hour >= bed && hour < wake else hour >= bed || hour < wake
    }

    /** 下一次醒来；睡着时所有回复都压到这个点。 */
    fun nextWake(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, wakeHour(now, characterId, shown, routine))
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun clampDelay(now: Long, minutes: Int): Long {
        val target = now + minutes.coerceIn(10, MAX_DELAY_MINUTES) * 60_000L
        return target
    }

    /** 按性格的短延迟，几秒到二十分钟，不强制十分钟起步。 */
    fun shortDelay(now: Long, minutes: Int): Long =
        now + minutes.coerceIn(0, 20) * 60_000L
}
