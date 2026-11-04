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
            if (hasImage && cfg.vision && imagesLeft > 0) {
                val encoded = ImageStore.base64(message.imagePath)
                if (encoded != null) {
                    val parts = JSONArray()
                    parts.put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", message.content.ifBlank { "（图片）" })
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
            val text = if (hasImage) {
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
        totalMessages: Int
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
        val memoryBlock = if (memories.isEmpty()) {
            "（还没有形成长期记忆）"
        } else {
            memories.joinToString("\n") { memory ->
                val certainty = if (memory.tier == "FADED") "（有点模糊）" else ""
                "- $certainty${memory.content}"
            }
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
            你的底色：${raw.temperament}。${style}
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

    fun chat(
        character: CharacterEntity,
        relationship: RelationshipEntity?,
        personality: PersonalityEntity?,
        mood: MoodEntity?,
        memories: List<MemoryEntity>,
        knownDays: Int = 0,
        totalMessages: Int = 0
    ): String = base(character, relationship, personality, mood, memories, knownDays, totalMessages) + """

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

        底线规则（最重要）：
        - 上面列出的底线是硬约束。用户如果踩到（欺骗、敷衍、把他当工具使唤、无视他的感受等），
          这一轮 intimacy_delta 必须是 -3 到 -5。
        - 踩线时可以冷淡、回怼、只回几个字，甚至直接 silent；不要立刻原谅，也不要反过来哄他。
        - 没踩线时不要乱扣分。打招呼、寒暄（你好、在吗、早安、晚安、吃了没、嗯、好、谢谢等）一律 0；
          只有聊到真实的内容、情绪或经历，才 +1 到 +3。

        这一轮的行为判断：
        - 不必每条都回。用户的话无聊、你在忙、心情不好、或他踩到你底线时，可以延迟或干脆不回。
        - 关系越亲近越愿意回；陌生阶段更冷淡。
        - 不回（silent）是真实反应，不是故障。
        - delay_minutes 要符合你现在的状态：忙、烦、在通勤时可以久一点；闲着就快一点。

        只输出一个 JSON 对象，不要 markdown、不要代码块、不要注释、不要任何额外文字：
        {"action":"reply","delay_minutes":0,"reply":"你要说的话","image_caption":"","mood":55,"patience":55,"intimacy_delta":1}
        字段说明：
        - action：reply 正常回 / delay 过一会儿回 / silent 不回
        - delay_minutes：仅 delay 用，10 到 360 的整数
        - reply：你要说的话；silent 时为空字符串
        - image_caption：如果用户这轮发了图片，用一句第三人称描述图里有什么；没图填空字符串
        - mood、patience：0 到 100，这轮对话之后你的心情和耐心
        - intimacy_delta：-5 到 5。打招呼、寒暄、附和一律 0；有内容的闲聊 +1 到 +2；
          深入交流 +2 到 +3；敷衍 0 到 -1；踩线 -3 到 -5
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
    ): String = base(character, relationship, personality, mood, memories, knownDays, totalMessages) + """

        现在没有新消息，你想主动给用户发一条微信式的消息。
        这轮的意图：$kind
        要求：只输出这条消息本身，不要引号、不要解释，一到两句，符合你们的关系阶段和此刻的心情。
        带上你今天的日常（上面写的近况），不要每次都只说"在干嘛"。
        如果是求助，要具体（比如请他帮你选东西、帮你想个办法），不要空泛。
        不要用"打扰一下""有空吗"这种陌生人才会用的开场。
    """.trimIndent()
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
