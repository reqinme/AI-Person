package qinme.aiperson

import java.util.Calendar
import kotlin.random.Random

/**
 * 她的生活作息：不是"睡/醒"两个状态，而是一天里在做什么。
 * 模板跟着身份走，用户可在设定页选；空字符串表示没设，按无固定作息处理。
 */
object Routine {
    const val WORKER = "worker"
    const val STUDENT = "student"
    const val FREELANCE = "freelance"
    const val NIGHT = "night"
    const val NONE = "none"

    val templates: List<Pair<String, String>> = listOf(
        WORKER to "上班族",
        STUDENT to "学生",
        FREELANCE to "自由职业",
        NIGHT to "夜班",
        NONE to "没固定作息"
    )

    fun label(id: String): String = templates.firstOrNull { it.first == id }?.second ?: "没设置"

    /** 此刻的状态，用来决定她回得多快、说话多急。 */
    data class Moment(
        val activity: String,
        val busy: Int,
        val tired: Boolean,
        val timeOfDay: String
    )

    private fun cal(now: Long) = Calendar.getInstance().apply { timeInMillis = now }

    private fun hour(now: Long): Int = cal(now).get(Calendar.HOUR_OF_DAY)

    private fun weekend(now: Long): Boolean {
        val day = cal(now).get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    /** 同一个夜晚要稳定：用角色 + 那一晚做种子，凌晨和半夜算同一夜。 */
    private fun nightSeed(now: Long, characterId: String): Int {
        val c = cal(now)
        if (c.get(Calendar.HOUR_OF_DAY) < 12) c.add(Calendar.DAY_OF_YEAR, -1)
        return characterId.hashCode() * 31 + c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** 今晚会不会熬夜：不是每天熬，同一个夜晚内保持一致。 */
    fun staysUpLate(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Boolean {
        if (routine == NIGHT) return false
        val nightPerson = shown.liveliness > 55 && shown.independence > 45
        val chance = if (nightPerson) 0.55f else 0.2f
        return Random(nightSeed(now, characterId)).nextFloat() < chance
    }

    /** 基础上床时间，可能超过 24 点（25 表示凌晨 1 点）。 */
    fun baseBed(now: Long, routine: String): Float = when (routine) {
        WORKER -> if (weekend(now)) 24.5f else 23f
        STUDENT -> if (weekend(now)) 25f else 24f
        FREELANCE -> 25f
        NIGHT -> 8f
        else -> 24f
    }

    /** 基础起床时间。 */
    fun baseWake(now: Long, routine: String): Int = when (routine) {
        WORKER -> if (weekend(now)) 9 else 7
        STUDENT -> if (weekend(now)) 10 else 7
        FREELANCE -> 9
        NIGHT -> 15
        else -> 8
    }

    /** 忙的程度：0 闲，1 有点事，2 正忙。 */
    fun busy(now: Long, routine: String): Int {
        val h = hour(now)
        val we = weekend(now)
        return when (routine) {
            WORKER -> if (we) {
                if (h in 11..14) 0 else 1
            } else when {
                h in 9..11 || h in 13..17 -> 2
                h == 8 || h == 12 || h in 18..19 -> 1
                else -> 0
            }
            STUDENT -> if (we) 0 else when {
                h in 8..11 || h in 14..16 -> 2
                h in 12..13 || h in 17..18 -> 1
                else -> 0
            }
            FREELANCE -> when {
                h in 10..11 || h in 14..17 || h in 19..21 -> 1
                else -> 0
            }
            NIGHT -> if (h >= 22 || h < 6) 2 else 0
            else -> 0
        }
    }

    /** 此刻在做什么；给她一个真实的生活，而不是随时待命。 */
    fun activity(now: Long, routine: String): String {
        val h = hour(now)
        val we = weekend(now)
        return when (routine) {
            WORKER -> if (we) {
                when {
                    h < 10 -> "还在赖床"
                    h < 13 -> "刚起来，在收拾"
                    h < 20 -> "在外面转转"
                    else -> "在家待着"
                }
            } else when {
                h < 7 -> "还在睡"
                h == 7 -> "在通勤"
                h in 8..11 -> "在上班"
                h in 12..13 -> "午休，在吃饭"
                h in 14..17 -> "在上班"
                h in 18..19 -> "刚下班，在路上"
                h in 20..22 -> "在家，刚吃完饭"
                else -> "准备睡了"
            }
            STUDENT -> if (we) {
                if (h < 11) "还在睡" else "在放松"
            } else when {
                h < 7 -> "还在睡"
                h == 7 -> "在去学校"
                h in 8..11 -> "在上课"
                h in 12..13 -> "午休"
                h in 14..16 -> "在上课"
                h in 17..18 -> "下课了，在吃饭"
                h in 19..22 -> "在写东西或刷手机"
                else -> "准备睡了"
            }
            FREELANCE -> when {
                h < 9 -> "还在睡"
                h in 9..11 -> "在赶活"
                h in 12..13 -> "在吃饭"
                h in 14..17 -> "在赶活"
                h in 18..19 -> "刚忙完，在吃饭"
                h in 20..23 -> "自己的时间，躺着刷手机"
                else -> "还没睡"
            }
            NIGHT -> when {
                h in 22..23 || h < 6 -> "在上班"
                h == 6 || h == 7 -> "刚下夜班，在回家的路上"
                h in 8..14 -> "在补觉"
                h in 15..18 -> "刚醒，在吃饭或收拾"
                else -> "自己的时间"
            }
            else -> when {
                h < 8 -> "还没起"
                h in 8..11 -> "在忙自己的事"
                h in 12..13 -> "在吃饭"
                h in 14..18 -> "在忙"
                h in 19..23 -> "自己的时间"
                else -> "准备睡了"
            }
        }
    }

    fun timeOfDay(now: Long): String = when (hour(now)) {
        in 5..8 -> "早上"
        in 9..11 -> "上午"
        in 12..13 -> "中午"
        in 14..17 -> "下午"
        in 18..19 -> "傍晚"
        in 20..23 -> "晚上"
        else -> "深夜"
    }

    /** 昨晚熬夜的人，第二天上午会困。 */
    fun isTired(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Boolean {
        if (routine == NIGHT) return false
        if (!staysUpLate(now, characterId, shown, routine)) return false
        return hour(now) in 6..13
    }

    fun moment(now: Long, characterId: String, shown: PersonalityEntity, routine: String): Moment =
        Moment(
            activity = activity(now, routine),
            busy = busy(now, routine),
            tired = isTired(now, characterId, shown, routine),
            timeOfDay = timeOfDay(now)
        )

    /** 忙的时候回得更慢，困的时候也会拖。 */
    fun delayFactor(now: Long, routine: String, tired: Boolean): Float {
        val busyFactor = when (busy(now, routine)) {
            2 -> 2.4f
            1 -> 1.4f
            else -> 1f
        }
        return busyFactor * if (tired) 1.4f else 1f
    }
}
