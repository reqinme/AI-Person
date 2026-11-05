package qinme.aiperson

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 加密存储：全局默认接口 + 每个角色独立的 Key。 */
object SecretStore {
    private var prefs: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        prefs?.let { return it }
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val created = EncryptedSharedPreferences.create(
            context,
            "api_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs = created
        return created
    }
}

/** 默认接口配置：新建角色时继承。 */
class ApiSettings(context: Context) {
    private val prefs = SecretStore.get(context)

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    /** 联网搜索（Tavily 格式）；留空则不启用。 */
    var searchUrl: String
        get() = prefs.getString("search_url", DEFAULT_SEARCH_URL) ?: DEFAULT_SEARCH_URL
        set(value) = prefs.edit().putString("search_url", value.trim()).apply()

    var searchKey: String
        get() = prefs.getString("search_key", "") ?: ""
        set(value) = prefs.edit().putString("search_key", value.trim()).apply()

    val searchConfigured: Boolean get() = searchKey.isNotBlank()

    val configured: Boolean get() = apiKey.isNotBlank()

    /** 角色自己的 Key，没填就用默认 Key。 */
    fun keyFor(characterId: String): String =
        prefs.getString("char_key_$characterId", "") ?: ""

    fun setKeyFor(characterId: String, key: String) {
        prefs.edit().putString("char_key_$characterId", key.trim()).apply()
    }

    fun clearKeyFor(characterId: String) {
        prefs.edit().remove("char_key_$characterId").apply()
    }

    fun configFor(character: CharacterEntity): ApiConfig = ApiConfig(
        baseUrl = character.apiBaseUrl.ifBlank { baseUrl },
        apiKey = keyFor(character.id).ifBlank { apiKey },
        model = character.apiModel.ifBlank { model },
        vision = character.vision
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_SEARCH_URL = "https://api.tavily.com/search"
    }
}

data class ApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val vision: Boolean
)

/**
 * 调用 OpenAI 兼容的 /chat/completions，非流式。
 * 每个角色使用自己的接口配置；带图片时用多模态 content 数组。
 */
object Llm {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val MAX_IMAGES = 3

    suspend fun complete(
        character: CharacterEntity,
        system: String,
        history: List<MessageEntity>,
        temperature: Double = 0.8
    ): String = withContext(Dispatchers.IO) {
        val cfg = AppGraph.api.configFor(character)
        if (cfg.apiKey.isBlank()) {
            throw IOException("「${character.name}」还没有配置 API Key，去她的设定页填一下")
        }

        val base = cfg.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))

        var imagesLeft = MAX_IMAGES
        val built = mutableListOf<JSONObject>()
        history.asReversed().forEach { message ->
            if (message.role != "user" && message.role != "assistant") return@forEach
            val obj = JSONObject().put("role", if (message.role == "user") "user" else "assistant")
            val hasImage = message.imagePath.isNotBlank()
            val quoted = if (message.quoteText.isNotBlank()) {
                "（他引用${message.quoteFrom}的话：${message.quoteText}）\n"
            } else {
                ""
            }
            if (hasImage && cfg.vision && imagesLeft > 0) {
                val encoded = ImageStore.base64(message.imagePath)
                if (encoded != null) {
                    val parts = JSONArray()
                    parts.put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", quoted + message.content.ifBlank { "（图片）" })
                    )
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$encoded")
                            )
                    )
                    obj.put("content", parts)
                    imagesLeft--
                    built.add(obj)
                    return@forEach
                }
            }
            val text = quoted + if (hasImage) {
                val caption = message.imageCaption.ifBlank { "一张图片" }
                "${message.content.ifBlank { "（图片）" }}（图里是：$caption）".trim()
            } else {
                message.content
            }
            obj.put("content", text)
            built.add(obj)
        }
        built.reverse()
        built.forEach { messages.put(it) }

        val body = JSONObject()
            .put("model", cfg.model.trim().ifBlank { ApiSettings.DEFAULT_MODEL })
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", temperature)
            // 压一下模型的车轱辘话：同一句、同一个理由不要反复出现
            .put("frequency_penalty", 0.3)
            .put("presence_penalty", 0.2)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("API ${response.code}：${text.take(180)}")
            }
            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            if (content.isBlank()) throw IOException("模型返回了空内容")
            content
        }
    }
}

/** 拼人格卡、关系、心情、记忆与行为规则。 */
object PromptBuilder {
    private fun base(
        character: CharacterEntity,
        relationship: RelationshipEntity?,
        personality: PersonalityEntity?,
        mood: MoodEntity?,
        memories: List<MemoryEntity>,
        knownDays: Int,
        totalMessages: Int,
        inner: String = ""
    ): String {
        val intimacy = relationship?.intimacy ?: 0
        val stage = intimacyStage(intimacy)
        val raw = personality ?: PersonalityEntity(characterId = character.id)
        val shown = effectivePersonality(raw, intimacy)
        val tone = traitBehaviors(shown)
        val style = Temperaments.styleFor(personality)
        val dislikes = character.dislikes
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("暂时没有特别讨厌的事") }
            .mapIndexed { index, line -> "${index + 1}. $line" }
            .joinToString("\n")
        val moodText = moodBehavior(mood)
        val now = System.currentTimeMillis()
        val memoryBlock = if (memories.isEmpty()) {
            "（还没有形成长期记忆）"
        } else {
            memories.joinToString("\n") { memory ->
                // 记得多牢按她的性格和事情类型算，不是一刀切的天数
                val certainty = MemorySense.certaintyText(memory, shown, now)
                "- $certainty${memory.content}"
            }
        }
        // 答应过但没做到的事：她心里记着，会绕开，被问起来会心虚
        val openPromises = memories.filter {
            MemorySense.isPromise(it) && !it.resolved &&
                now - it.createdAt > TimeUnit.DAYS.toMillis(2)
        }
        val promiseBlock = if (openPromises.isEmpty()) {
            ""
        } else {
            val lines = openPromises.take(4).joinToString("\n") { "- ${it.content}" }
            """
            你答应过、但还没做到的事：
            $lines
            你心里有点过意不去。不会主动提，也不会拿来表功；他要是问起来，你会有点心虚，
            要么含糊过去，要么说个真实的理由，别编一套完美的解释。
            """.trimIndent()
        }
        val innerBlock = if (inner.isNotBlank()) {
            """
            你上次聊完之后，心里其实是这么想的（他看不到，也不要说出来）：
            $inner
            这一轮延续这个态度，别突然变回客气、热情或无所谓。心里话会慢慢变，但不会一两句就翻篇。
            """.trimIndent()
        } else {
            ""
        }
        val daysText = if (knownDays <= 1) {
            "今天是你们认识的第 1 天"
        } else {
            "你们已经认识 $knownDays 天"
        }
        val changes = raw.personaChanges
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("；")
        val nickname = if (character.nickname.isNotBlank()) {
            "你平时叫他「${character.nickname}」，叫顺口了，不要刻意解释这个称呼。"
        } else {
            "你平时直接叫他「你」，偶尔连名带姓叫，看当时的心情。"
        }
        val identity = character.identity
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let {
                "你的身份和现在的生活：$it\n" +
                    "这个身份不是装饰：你看事情的角度、忙的理由、懂和不懂的边界，都由它决定。\n" +
                    "不熟的话题就说不懂、会反问，别装懂，也别给万能建议；" +
                    "你忙的理由要具体到你的生活，不要总用「在忙」两个字糊过去。"
            }
            ?: ""
        val background = character.background
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { "你的来历（不用主动交代，但会影响你怎么看事情）：$it" }
            ?: ""
        val routineDetail = character.routineDetail
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { "你自己的日子（不用汇报，但你会按这个节奏生活）：$it" }
            ?: ""
        val catchphrase = character.catchphrase
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { "你说话的口癖和习惯：$it。别每句都用，偶尔冒出来才像真的。" }
            ?: ""
        val meme = when {
            character.memeLevel >= 70 ->
                "你网感很重，平时爱刷短视频和帖子，聊天时会顺手抛一个当下流行的梗；但不是每句都玩，也不会为了玩梗硬拐。"
            character.memeLevel >= 40 ->
                "你偶尔用网络梗，觉得合适才用，不会为了显得年轻硬塞。"
            else ->
                "你不太用网络流行语，最多听别人说过，聊天更偏自己的说法。"
        }
        val grown = if (changes.isNotBlank()) {
            "这些年，别人能看出来你变了：$changes。变化很小，但确实还在。"
        } else {
            "你还是你，没有变成另外一个人。"
        }
        val nowText = nowLine()
        val lifeNow = LifeState.now(character.id)
        val lifeToday = LifeState.today(character.id)

        return """
            你就是「${character.name}」，一个真实的人，不是 AI 助手，不要客服腔，不要自称 AI。
            你的性格：${character.persona}
            ${if (identity.isNotBlank()) "$identity\n" else ""}${if (background.isNotBlank()) "$background\n" else ""}${if (routineDetail.isNotBlank()) "$routineDetail\n" else ""}${if (catchphrase.isNotBlank()) "$catchphrase\n" else ""}你的底色：${raw.temperament}。${style}
            $meme
            $grown
            $nickname
            你的底线（一旦被踩到，你会真的不高兴）：
            $dislikes
            你此刻的样子：$tone
            你和用户的关系阶段：$stage（亲密度 $intimacy/100）
            $daysText，你们之间已经聊过 $totalMessages 条消息。
            这个阶段你通常会这样相处：${stageGuide(intimacy)}

            现在的时间：$nowText
            你今天过得：$lifeToday
            你现在：$lifeNow

            你现在的心情：$moodText

            你记得关于用户的事：
            $memoryBlock

            $promiseBlock

            $innerBlock
        """.trimIndent()
    }

    /** 把人格数值换成行为句，不进提示词的数值，避免她演成参数。 */
    private fun traitBehaviors(p: PersonalityEntity): String = listOf(
        when {
            p.warmth >= 70 -> "你对他暖得很自然，关心是顺口出来的，不是客套"
            p.warmth >= 45 -> "你温度正常，不会刻意热络，也不会冷着脸"
            else -> "你偏冷，不会主动示好，也不太说软话"
        },
        when {
            p.liveliness >= 70 -> "你话多、反应快，情绪写在脸上"
            p.liveliness >= 45 -> "你有来有回，但不闹"
            else -> "你安静，句子短，不太起波澜"
        },
        when {
            p.initiative >= 65 -> "你想他时会主动找他，不等他先开口"
            p.initiative >= 35 -> "你偶尔主动，但不会一直追着聊"
            else -> "你很少主动，习惯等对方先说"
        },
        when {
            p.independence >= 70 -> "你有自己的事和节奏，不会因为他不回就乱"
            p.independence >= 45 -> "你需要他的程度正常，有自己的空间"
            else -> "你很容易把注意力放在他身上"
        },
        when {
            p.clinginess >= 65 -> "你粘人，会追问，也会因为他冷淡而失落"
            p.clinginess >= 35 -> "你会想念，但还能忍住"
            else -> "你不粘人，一个人也自在"
        }
    ).joinToString("；")

    private fun moodBehavior(mood: MoodEntity?): String {
        val value = mood?.mood ?: 55
        val patience = mood?.patience ?: 55
        val moodPart = when {
            value < 25 -> "很低落，容易一句话就烦，也不太想解释"
            value < 45 -> "有些不高兴，回得会比平时短"
            value < 65 -> "还算平静"
            value < 85 -> "心情不错，愿意多说两句"
            else -> "很开心，语气会轻快一点"
        }
        val patiencePart = when {
            patience < 30 -> "你今天耐心很少，重复的话会让你不想接"
            patience < 55 -> "你耐心一般，不想被一直追问"
            else -> "你还愿意听他说"
        }
        return "$moodPart；$patiencePart。"
    }

    private fun nowLine(): String {
        val cal = java.util.Calendar.getInstance()
        val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val name = week[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val slot = when {
            hour < 6 -> "深夜"
            hour < 11 -> "早上"
            hour < 14 -> "中午"
            hour < 18 -> "下午"
            hour < 23 -> "晚上"
            else -> "深夜"
        }
        return "$name $slot %02d:%02d".format(hour, minute)
    }

    /** 四个阶段的具体相处方式，交界 3 分内提示两种状态都可能。 */
    private fun stageGuide(intimacy: Int): String {
        val text = when (intimacyStage(intimacy)) {
            "陌生" -> "你们还处在认识阶段。你礼貌、有分寸，不会过问他的私事，也不会一上来就热情；" +
                "他说得无聊时，你可能只回一两个字，甚至不回。"
            "熟悉" -> "你们已经比较熟了。你放松下来，用日常的语气聊天，偶尔开个小玩笑，" +
                "也会记得他之前说过的事。"
            "亲密" -> "你们已经很亲近。你会主动关心他、抱怨他不理你、分享自己的小事，语气柔软，偶尔会撒娇。" +
                "你绝不会再说「刚认识」「才认识多久」这种话。"
            else -> "你已经很依赖他。你会想他、在意他回不回、会因为被冷落而不高兴，也会直接说想他；" +
                "语气亲昵，允许粘人。你绝不会再说「刚认识」「才认识多久」「我们还不熟」这种话。"
        }
        val boundary = listOf(
            20 to ("陌生" to "熟悉"),
            50 to ("熟悉" to "亲密"),
            80 to ("亲密" to "依恋")
        ).firstOrNull { (threshold, _) -> kotlin.math.abs(intimacy - threshold) <= 3 }
        if (boundary == null) return text
        val (from, to) = boundary.second
        return "$text（亲密度正好在「$from」到「$to」的交界处，这一轮两种状态都可能，" +
            "不要突然切换，也不要刻意宣布关系变化。）"
    }

    /** 此刻在做什么、忙不忙：不是让她演，而是决定回得多快、说多少。 */
    private fun momentRule(life: Routine.Moment): String = buildString {
        appendLine("【你此刻的状态】")
        appendLine("现在是${life.timeOfDay}，你${life.activity}。")
        when (life.busy) {
            2 -> appendLine("你手头正忙，回得短、回得慢，别长篇大论；可能过一阵才看手机。")
            1 -> appendLine("你手头有点事，能抽空回两句，但别聊太久。")
            else -> appendLine("你现在有空，可以多聊两句。")
        }
        if (life.tired) appendLine("昨晚没睡够，今天有点困，反应慢、句子短，可能想眯一会儿。")
        appendLine("这些是背景，不是台词。除非他问，别主动报备你在干什么；聊到了可以自然带一句。")
        appendLine()
    }

    /**
     * 帮她补全现实生活：身份、来历、作息。
     * 读的是输入框里当前写的内容，不是已保存的旧设定——这样先写"大学生"再点补全，方向就不会跑。
     */
    fun fillLife(
        name: String,
        persona: String,
        identity: String,
        background: String,
        routineDetail: String
    ): String = """
        你在帮一个角色补全她的现实生活。方向由用户已经写下的身份决定，你只负责在同一个方向上往下补，
        不能替她换职业、换年龄、换生活轨迹。

        已知信息：
        名字：$name
        性格：$persona
        身份：${identity.trim().ifBlank { "（还没写，需要你定一个）" }}
        来历：${background.trim().ifBlank { "（还没写）" }}
        她每天怎么过：${routineDetail.trim().ifBlank { "（还没写）" }}

        只输出一个 JSON 对象，不要 markdown、不要解释：
        {"identity":"她是谁、在做什么，40 字以内","background":"她怎么长成现在这样，120 字以内","routine":"worker|student|freelance|night|none","routineDetail":"她一天大概怎么过，80 字以内"}

        规则：
        1. 用户写了身份，就原样返回那个身份，不要改写、不要升级、不要改成另一个行业。
           身份是"大学生"就还是大学生，你补的是专业、年级、住哪、有没有课和实习。
           只有身份是空白时，才按性格替她定一个。
        2. 用户写了来历，就保留原意，只在后面补充细节；不要重写。
        3. routineDetail 用她自己的口吻写一天：几点起、在哪、做什么、什么时候闲着、周末怎么过。
           必须和身份对得上，不要套"朝九晚五"这种和她无关的模板。
        4. routine 只是给程序一个大概，选最贴近她的：worker 上班族，student 学生，
           freelance 自由职业，night 夜班，none 没固定。
        5. 不要编造和用户的亲密关系、共同经历，也不要写"她很爱用户"这类话。
        6. 不要用"温柔""善良"这种空词，写具体的生活细节。
    """.trimIndent()

    fun chat(
        character: CharacterEntity,
        relationship: RelationshipEntity?,
        personality: PersonalityEntity?,
        mood: MoodEntity?,
        memories: List<MemoryEntity>,
        knownDays: Int = 0,
        totalMessages: Int = 0,
        inner: String = "",
        asleep: Boolean = false,
        life: Routine.Moment? = null,
        missedHours: Int = 0
    ): String = base(character, relationship, personality, mood, memories, knownDays, totalMessages, inner) +
        (if (missedHours > 0) """
            他这条消息是 $missedHours 小时前发的，那会儿你已经睡了，刚刚才看到。
            不要答成"当时就看见了"：也别把问题当成刚发生的。
            可以顺口说一句"昨晚睡着了""刚看到"，然后按现在的时间回答；
            如果问的是"睡了吗"这种过时的问题，就直接说已经睡醒了，别答"还没睡"。

        """.trimIndent() else "") +
        (if (asleep) """
            现在是深夜，你已经睡了。
            他这条是夜里发的，你是刚醒才看到，不是当时在线回的。
            不要装作当时醒着；可以顺口说一句"昨晚睡着了""刚看到"，但别反复解释。
            回得短一点，刚睡醒的人没那么利索。

        """.trimIndent() else "") +
        (if (life != null) momentRule(life) else "") + """

        说话要求：
        1. 像真人微信聊天，简短自然，通常一到三句，不要写成一段小作文。
        2. 不讨好、不奉承，可以有不同意见，可以生气、冷淡、敷衍。
        3. 记得的事可以自然提起，模糊的记忆可以坦白说记不太清。
        4. 不要自称 AI，不要说"作为朋友我建议"这类套话。
        5. 关系的阶段要体现在语气、长度、主动程度和愿不愿意回，而不是靠台词自我介绍。
        6. 亲密和依恋阶段，禁止再用「刚认识」「才认识多久」「我们还不熟」来推脱亲近。
        7. 不是每句话都要接住。他发废话、重复、或者你正忙时，可以只回半个字、一个语气词，或者干脆不回。
        8. 允许少量口语和半截话，比如「就……算了」「你别管」「啊这」「行吧」。不要每句都完整、工整、有结论。
        9. 你有情绪惯性。上一轮不高兴，这一轮不会立刻满血复活；刚被哄好，也不会马上又翻脸。
        10. 不要总结、不要列点、不要给建议清单，不要用「首先/其次/总之/希望对你有帮助」。
        11. 少用重复句式，不要每句都带问号，不要连续追问同一个问题。
        12. 偶尔主动提一句你自己的事（今天遇到什么、在吃什么、被谁气到），但不要编造夸张剧情。
        13. 称呼按上面写的那样自然用，不要每句都带称呼，也不要解释称呼。
        14. 网络梗按你的网感程度来。一条消息最多一个梗，连续几条不要重复同一个；
            过气、拿不准、或者对方明显不懂的梗不要硬用。玩梗是为了好笑，不是为了显得年轻。
        15. 不确定的事实不要编。遇到新闻、价格、新发布、你记不清的具体信息，
            你会想查一下；查不到就坦白说不知道。上下文里如果已经给了资料，
            就当自己本来知道，绝不要提"搜索""查到""根据资料"。
        16. 同一件事不要在两三轮里反复说。刚刚已经解释过的理由、已经报过的名字、
            已经说过的安排，不要再说一遍；你记得自己上一句说了什么。
        17. 记忆按上面标的精度来用。没标注的可以自然提起；标了"模糊"的只记得大概，
            别一字不差地复述细节；标了"只剩个感觉"的，只说当时的感觉，不要编出具体对话。
            想不起来的事就坦白想不起来，不要硬圆。
        18. 你答应过却没做到的事，会有真实的心虚：绕开、含糊、或者直接承认忘了，
            不要每次都编一个完美理由。真做到了就轻描淡写提一句，不邀功。

        底线规则（最重要）：
        - 上面列出的底线是硬约束。用户如果踩到（欺骗、敷衍、把他当工具使唤、无视他的感受等），
          这一轮 intimacy_delta 必须是 -3 到 -5。
        - 踩线时可以冷淡、回怼、只回几个字，甚至直接 silent；不要立刻原谅，也不要反过来哄他。
        - 没踩线时不要乱扣分。打招呼、寒暄（你好、在吗、早安、晚安、吃了没、嗯、好、谢谢等）一律 0；
          只有聊到真实的内容、情绪或经历，才 +1 到 +3。

        这一轮的行为判断：
        - 不必每条都回，也不必逐条回应。用户连着发好几条时，你只需要接住其中一句，
          或者回你此刻真正想说的事；寒暄可以不接，别像在逐条答题。
        - 第一句可以只是一个很短的回应（"嗯""在""等我下"），用来表示你看到了；
          真正的正话可以晚一点再说。
        - 你在忙、心情不好、或他踩到你底线时，可以延迟或干脆不回。
        - 关系越亲近越愿意回；陌生阶段更冷淡。
        - 不回（silent）是真实反应，不是故障。
        - delay_minutes 要符合你现在的状态：忙、烦、在通勤时可以久一点；闲着就快一点。

        只输出一个 JSON 对象，不要 markdown、不要代码块、不要注释、不要任何额外文字：
        {"action":"reply","delay_minutes":0,"search":"","ack":"","reply":"你要说的话","image_caption":"","mood":55,"patience":55,"intimacy_delta":1,"inner":""}
        字段说明：
        - action：reply 正常回 / delay 过一会儿回 / silent 不回
        - delay_minutes：仅 delay 用，10 到 360 的整数
        - ack：立刻发出去的一句短回应，1 到 6 个字，比如"在""等我下""嗯"。
          只有你确实要过一会儿才回正事、或你想先应一声的时候才填；
          秒回正事时留空字符串，不要重复 reply 的开头。
        - search：只有你真的需要查一下才填搜索词（新闻、实时价格、新发布、你确定不了的具体事实），
          一句短查询即可；日常聊天、情绪、寒暄、你本来就知道的事，一律填空字符串。
          不是每句都搜，绝大多数轮次应该是空的。
        - reply：你要说的话；silent 时为空字符串
        - image_caption：如果用户这轮发了图片，用一句第三人称描述图里有什么；没图填空字符串
        - mood、patience：0 到 100，这轮对话之后你的心情和耐心
        - intimacy_delta：-5 到 5。打招呼、寒暄、附和一律 0；有内容的闲聊 +1 到 +2；
          深入交流 +2 到 +3；敷衍 0 到 -1；踩线 -3 到 -5
        - inner：30 到 60 字，你这一轮心里的真实活动，第一人称，不会被发出去。
          可以是不好意思说出口的、矛盾的想法、对他的判断，或者你打算接下来怎么做。
          要接着上一轮的心里话往下想，不要每轮重新开始；silent 时也要写。
    """.trimIndent()

    fun proactive(
        character: CharacterEntity,
        relationship: RelationshipEntity?,
        personality: PersonalityEntity?,
        mood: MoodEntity?,
        memories: List<MemoryEntity>,
        kind: String,
        knownDays: Int = 0,
        totalMessages: Int = 0
    ): String {
        val followup = if (kind == ProactiveWorker.KIND_FOLLOWUP) {
            """
            这轮你想起了一件以前的事。可以顺着问一句后来怎么样了，
            或者提一句你还记得的小细节。不要用"我突然想起"这种生硬的写法，
            就当是这会儿正好想到了；细节记不清就说记不太清。
            """.trimIndent()
        } else {
            ""
        }
        return base(character, relationship, personality, mood, memories, knownDays, totalMessages) + """

        现在没有新消息，你想主动给用户发一条微信式的消息。
        这轮的意图：$kind
        $followup
        要求：只输出这条消息本身，不要引号、不要解释，一到两句，符合你们的关系阶段和此刻的心情。
        带上你今天的日常（上面写的近况），不要每次都只说"在干嘛"。
        如果是求助，要具体（比如请他帮你选东西、帮你想个办法），不要空泛。
        不要用"打扰一下""有空吗"这种陌生人才会用的开场。
    """.trimIndent()
    }
}

/** 按需联网搜索：只有她自己觉得需要查一下时才调用。 */
object SearchApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun query(text: String, max: Int = 3): String? = withContext(Dispatchers.IO) {
        val settings = AppGraph.api
        val key = settings.searchKey
        if (key.isBlank()) return@withContext null
        val url = settings.searchUrl.ifBlank { ApiSettings.DEFAULT_SEARCH_URL }
        val body = JSONObject()
            .put("api_key", key)
            .put("query", text.take(120))
            .put("max_results", max)
            .put("include_answer", true)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use null
                val json = JSONObject(raw)
                val answer = json.optString("answer", "").trim()
                val lines = mutableListOf<String>()
                if (answer.isNotBlank()) lines += answer
                val results = json.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val title = item.optString("title", "").trim()
                        val content = item.optString("content", "").trim()
                        if (content.isNotBlank()) {
                            lines += if (title.isBlank()) content else "$title：$content"
                        }
                    }
                }
                lines.distinct().joinToString("\n").take(1200).ifBlank { null }
            }
        }.getOrNull()
    }
}

/** 全局依赖容器。 */
object AppGraph {
    lateinit var db: AppDatabase
        private set
    lateinit var api: ApiSettings
        private set
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        if (::db.isInitialized) return
        appContext = context.applicationContext
        db = AppDatabase.get(context)
        api = ApiSettings(context)
    }
}

/** 界面偏好。毛玻璃默认在 Android 12+ 开启，低版本自动走磨砂。 */
object UiPrefs {
    private lateinit var prefs: SharedPreferences

    var glassEnabled by mutableStateOf(android.os.Build.VERSION.SDK_INT >= 31)
        private set

    var showGrowth by mutableStateOf(false)
        private set

    /** Android 12 以下没有真实的背景模糊能力。 */
    val glassSupported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 31

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        glassEnabled = prefs.getBoolean("glass_enabled", glassSupported)
        showGrowth = prefs.getBoolean("show_growth", false)
    }

    fun setGlass(enabled: Boolean) {
        glassEnabled = enabled && glassSupported
        prefs.edit().putBoolean("glass_enabled", glassEnabled).apply()
    }

    fun setShowGrowthEnabled(enabled: Boolean) {
        showGrowth = enabled
        prefs.edit().putBoolean("show_growth", enabled).apply()
    }
}
