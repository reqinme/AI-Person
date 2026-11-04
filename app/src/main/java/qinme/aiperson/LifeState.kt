package qinme.aiperson

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 她自己的日常。
 * 同一天、同一个时段，选定后就固定，不会每说一句话就换一个场景；
 * 全部在本地挑选，不额外调接口。
 */
object LifeState {

    private val morningWeekday = listOf(
        "今天起得有点晚，早饭随便买了个包子",
        "今天一早被闹钟吵醒，脑子到现在还没转过来",
        "今天在通勤路上被人踩了一脚，鞋都脏了",
        "今天刚到公司就被拉进一个会，挺烦的",
        "今天天气有点阴，出门忘带伞了"
    )

    private val morningWeekend = listOf(
        "今天睡到自然醒，窗帘都没拉开",
        "今天一早在厨房折腾半天，做了个不太成功的早饭",
        "今天周末，赖在床上刷了会儿手机",
        "今天打算出门走走，还没想好去哪"
    )

    private val noonWeekday = listOf(
        "今天中午在工位上随便吃了点，饭都凉了",
        "今天午休被同事拉着聊了半天八卦",
        "今天中午点的外卖送晚了，饿得有点烦",
        "今天中午在楼下吃了碗面，人特别多"
    )

    private val noonWeekend = listOf(
        "今天中午在家里煮了面，味道还行",
        "今天中午出去吃了顿好的，算犒劳自己",
        "今天中午懒得做饭，随便对付了一口",
        "今天中午在咖啡馆坐了一会儿"
    )

    private val afternoonWeekday = listOf(
        "今天下午一直在改东西，改到有点烦",
        "今天下午被临时塞了活，正忙着",
        "今天下午开完会脑子是空的",
        "今天下午在工位上发呆，想早点下班",
        "今天下午被同事气到，不想说话"
    )

    private val afternoonWeekend = listOf(
        "今天下午在街上瞎逛，没什么目的",
        "今天下午在家里看了半部电影",
        "今天下午在收拾房间，越收越乱",
        "今天下午在咖啡馆待着，旁边太吵了"
    )

    private val eveningWeekday = listOf(
        "今天刚下班，在地铁上站着，累",
        "今天加班到这会儿，饭还没吃",
        "今天下班路上买了点吃的，正在回家",
        "今天回到家就往沙发上一躺，什么都不想干"
    )

    private val eveningWeekend = listOf(
        "今天傍晚出去走了走，风挺舒服的",
        "今天在超市逛了半天，买了些没用的东西",
        "今天晚饭吃得有点撑，正在缓",
        "今天在家做了顿饭，厨房一团糟"
    )

    private val night = listOf(
        "今天已经躺床上了，还没关灯",
        "今天有点睡不着，在翻手机",
        "今天刚洗完澡，头发还没干",
        "今天准备睡了，但还不想放下手机",
        "今天夜里安静下来，脑子反而有点乱"
    )

    /** 今天过得怎么样，全天固定一句。 */
    fun today(characterId: String, ts: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val weekend = isWeekend(cal)
        val pool = if (weekend) {
            morningWeekend + noonWeekend + afternoonWeekend + eveningWeekend
        } else {
            morningWeekday + noonWeekday + afternoonWeekday + eveningWeekday
        }
        return pick(key(characterId, ts, "today", weekend), pool)
    }

    /** 这个时段她正在做什么。 */
    fun now(characterId: String, ts: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val weekend = isWeekend(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val pool = when {
            hour < 6 -> night
            hour < 11 -> if (weekend) morningWeekend else morningWeekday
            hour < 14 -> if (weekend) noonWeekend else noonWeekday
            hour < 18 -> if (weekend) afternoonWeekend else afternoonWeekday
            hour < 23 -> if (weekend) eveningWeekend else eveningWeekday
            else -> night
        }
        return pick(key(characterId, ts, "now", weekend, hour / 3), pool)
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val day = cal.get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    private fun key(characterId: String, ts: Long, tag: String, vararg extra: Any): String {
        val day = ts / TimeUnit.DAYS.toMillis(1)
        return "$characterId|$day|$tag|${extra.joinToString(":")}"
    }

    private fun pick(key: String, pool: List<String>): String {
        val index = ((key.hashCode() % pool.size) + pool.size) % pool.size
        return pool[index]
    }
}
