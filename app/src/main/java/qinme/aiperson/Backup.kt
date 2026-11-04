package qinme.aiperson

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份是一个 zip：backup.json + media/ 下的图片。
 * 不含 API Key，只留接口地址和模型名；导入后需要重新填 Key。
 */
object Backup {

    // 备份图片的大小上限，防止恶意/损坏的 zip 把手机存储写满
    private const val MAX_MEDIA_FILE = 20L * 1024 * 1024
    private const val MAX_MEDIA_TOTAL = 300L * 1024 * 1024

    suspend fun export(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppGraph.db
            val characters = db.characterDao().all()
            val messages = characters.flatMap { db.messageDao().all(it.id) }
            val memories = db.memoryDao().all()
            val relationships = db.relationshipDao().all()
            val personalities = db.personalityDao().all()
            val personaHistory = db.personaHistoryDao().all()
            val moods = characters.mapNotNull { db.moodDao().get(it.id) }

            val root = JSONObject()
                .put("version", 3)
                .put("exportedAt", System.currentTimeMillis())
                .put("note", "API Key 不包含在备份中，导入后需要为每个角色重新填写。")
                .put("characters", JSONArray().apply { characters.forEach { put(it.toJson()) } })
                .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
                .put("memories", JSONArray().apply { memories.forEach { put(it.toJson()) } })
                .put("relationships", JSONArray().apply {
                    relationships.forEach { item ->
                        put(
                            JSONObject()
                                .put("characterId", item.characterId)
                                .put("intimacy", item.intimacy)
                                .put("lastChatAt", item.lastChatAt)
                        )
                    }
                })
                .put("personalities", JSONArray().apply {
                    personalities.forEach { item ->
                        put(
                            JSONObject()
                                .put("characterId", item.characterId)
                                .put("warmth", item.warmth)
                                .put("liveliness", item.liveliness)
                                .put("initiative", item.initiative)
                                .put("independence", item.independence)
                                .put("clinginess", item.clinginess)
                                .put("prefWarmth", item.prefWarmth)
                                .put("prefLiveliness", item.prefLiveliness)
                                .put("prefInitiative", item.prefInitiative)
                                .put("prefIndependence", item.prefIndependence)
                                .put("prefClinginess", item.prefClinginess)
                                .put("temperament", item.temperament)
                                .put("baseWarmth", item.baseWarmth)
                                .put("baseLiveliness", item.baseLiveliness)
                                .put("baseInitiative", item.baseInitiative)
                                .put("baseIndependence", item.baseIndependence)
                                .put("baseClinginess", item.baseClinginess)
                                .put("personaChanges", item.personaChanges)
                                .put("lastPersonaChangeAt", item.lastPersonaChangeAt)
                        )
                    }
                })
                .put("personaHistory", JSONArray().apply {
                    personaHistory.forEach { item ->
                        put(
                            JSONObject()
                                .put("characterId", item.characterId)
                                .put("change", item.change)
                                .put("reason", item.reason)
                                .put("createdAt", item.createdAt)
                        )
                    }
                })
                .put("moods", JSONArray().apply {
                    moods.forEach { item ->
                        put(
                            JSONObject()
                                .put("characterId", item.characterId)
                                .put("mood", item.mood)
                                .put("patience", item.patience)
                                .put("updatedAt", item.updatedAt)
                        )
                    }
                })

            val media = buildSet {
                characters.forEach {
                    if (it.avatarPath.isNotBlank()) add(it.avatarPath)
                    if (it.backgroundPath.isNotBlank()) add(it.backgroundPath)
                }
                messages.forEach { if (it.imagePath.isNotBlank()) add(it.imagePath) }
            }

            context.contentResolver.openOutputStream(uri)?.use { output ->
                zip(output, root.toString(), media, context)
            } ?: error("无法写入备份文件")
        }
    }

    private fun zip(output: OutputStream, json: String, media: Set<String>, context: Context) {
        ZipOutputStream(output.buffered()).use { zout ->
            zout.putNextEntry(ZipEntry("backup.json"))
            zout.write(json.toByteArray())
            zout.closeEntry()
            media.forEach { name ->
                val safe = safeName(name) ?: return@forEach
                val file = ImageStore.file(context, safe)
                if (!file.exists()) return@forEach
                zout.putNextEntry(ZipEntry("media/$safe"))
                file.inputStream().use { it.copyTo(zout) }
                zout.closeEntry()
            }
        }
    }

    suspend fun import(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // 第一遍只读 backup.json，先完整解析；解析失败时绝不动现有数据。
            var json: String? = null
            context.contentResolver.openInputStream(uri).use { raw ->
                val input = raw ?: error("无法读取备份文件")
                ZipInputStream(input.buffered()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        if (entry.name == "backup.json") {
                            json = zin.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }

            val root = JSONObject(json ?: error("备份文件里没有 backup.json"))

            val characters = root.array("characters").map { it.toCharacter() }
            val messages = root.array("messages").map { it.toMessage() }
            val memories = root.array("memories").map { it.toMemory() }
            val relationships = root.array("relationships").map {
                RelationshipEntity(
                    characterId = it.getString("characterId"),
                    intimacy = it.optInt("intimacy", 0),
                    lastChatAt = it.optLong("lastChatAt", 0)
                )
            }
            val personalities = root.array("personalities").map {
                val warmth = it.optInt("warmth", 50)
                val liveliness = it.optInt("liveliness", 50)
                val initiative = it.optInt("initiative", 40)
                val independence = it.optInt("independence", 50)
                val clinginess = it.optInt("clinginess", 30)
                PersonalityEntity(
                    characterId = it.getString("characterId"),
                    warmth = warmth,
                    liveliness = liveliness,
                    initiative = initiative,
                    independence = independence,
                    clinginess = clinginess,
                    prefWarmth = it.optInt("prefWarmth", warmth),
                    prefLiveliness = it.optInt("prefLiveliness", liveliness),
                    prefInitiative = it.optInt("prefInitiative", initiative),
                    prefIndependence = it.optInt("prefIndependence", independence),
                    prefClinginess = it.optInt("prefClinginess", clinginess),
                    temperament = it.optString("temperament", "自定义"),
                    baseWarmth = it.optInt("baseWarmth", warmth),
                    baseLiveliness = it.optInt("baseLiveliness", liveliness),
                    baseInitiative = it.optInt("baseInitiative", initiative),
                    baseIndependence = it.optInt("baseIndependence", independence),
                    baseClinginess = it.optInt("baseClinginess", clinginess),
                    personaChanges = it.optString("personaChanges", ""),
                    lastPersonaChangeAt = it.optLong("lastPersonaChangeAt", 0)
                )
            }
            val personaHistory = root.array("personaHistory").map {
                PersonaChangeEntity(
                    characterId = it.getString("characterId"),
                    change = it.optString("change", ""),
                    reason = it.optString("reason", ""),
                    createdAt = it.optLong("createdAt", 0)
                )
            }
            val moods = root.array("moods").map {
                MoodEntity(
                    characterId = it.getString("characterId"),
                    mood = it.optInt("mood", 55),
                    patience = it.optInt("patience", 55),
                    updatedAt = it.optLong("updatedAt", 0)
                )
            }

            val db = AppGraph.db
            // 整体事务：中途失败不会留下半套数据
            db.withTransaction {
                db.messageDao().clear()
                db.memoryDao().clear()
                db.relationshipDao().clear()
                db.personalityDao().clear()
                db.personaHistoryDao().clear()
                db.moodDao().clear()
                db.characterDao().clear()

                characters.forEach { db.characterDao().upsert(it) }
                messages.forEach { db.messageDao().insert(it) }
                memories.forEach { db.memoryDao().insert(it) }
                relationships.forEach { db.relationshipDao().upsert(it) }
                personalities.forEach { db.personalityDao().upsert(it) }
                personaHistory.forEach { db.personaHistoryDao().insert(it) }
                moods.forEach { db.moodDao().upsert(it) }
            }

            // 覆盖导入时，旧的亲密度内部累计/压缩进度可能和新数据不一致，重置成导入值
            relationships.forEach {
                BehaviorSettings.setIntimacyRaw(it.characterId, it.intimacy)
                BehaviorSettings.resetIdlePenalty(it.characterId)
                BehaviorSettings.setLastEvalId(it.characterId, 0L)
            }

            // 数据落库后再解压图片；缺失的图片不影响文字内容。
            extractMedia(context, uri)

            characters.size
        }
    }

    private fun extractMedia(context: Context, uri: Uri) {
        val writtenFiles = mutableListOf<File>()
        runCatching {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name.startsWith("media/")) {
                            // 只接受 media/ 下的单层文件名，挡住 ../ 和绝对路径
                            val safe = safeName(name.removePrefix("media/"))
                            if (safe != null) {
                                val target = ImageStore.file(context, safe)
                                val written = runCatching {
                                    var size = 0L
                                    target.outputStream().use { out ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val read = zin.read(buffer)
                                            if (read < 0) break
                                            if (read == 0) continue
                                            size += read
                                            if (size > MAX_MEDIA_FILE) error("单张图片超过上限")
                                            out.write(buffer, 0, read)
                                        }
                                    }
                                    size
                                }.getOrNull()
                                if (written == null) {
                                    target.delete()
                                } else {
                                    writtenFiles.add(target)
                                    total += written
                                    if (total > MAX_MEDIA_TOTAL) error("备份图片总量超过上限")
                                }
                            }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
        }.onFailure {
            // 超限或损坏时，把已经写出来的半成品清掉
            writtenFiles.forEach { it.delete() }
        }
    }

    private fun safeName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 128) return null
        if (name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\') || name.contains(':')) return null
        return name
    }

    private fun JSONObject.array(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) add(array.getJSONObject(i))
        }
    }

    private fun CharacterEntity.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("gender", gender)
        .put("persona", persona)
        .put("accentColor", accentColor)
        .put("createdAt", createdAt)
        .put("dislikes", dislikes)
        .put("avatarPath", avatarPath)
        .put("backgroundPath", backgroundPath)
        .put("pinned", pinned)
        .put("strongAlertAt", strongAlertAt)
        .put("apiBaseUrl", apiBaseUrl)
        .put("apiModel", apiModel)
        .put("vision", vision)
        .put("nickname", nickname)
        .put("identity", identity)
        .put("background", background)
        .put("catchphrase", catchphrase)
        .put("memeLevel", memeLevel)

    private fun JSONObject.toCharacter() = CharacterEntity(
        id = getString("id"),
        name = getString("name"),
        gender = optString("gender", ""),
        persona = optString("persona", ""),
        accentColor = optInt("accentColor", 0xFF0FDC78.toInt()),
        createdAt = optLong("createdAt", 0),
        dislikes = optString("dislikes", ""),
        avatarPath = optString("avatarPath", ""),
        backgroundPath = optString("backgroundPath", ""),
        pinned = optBoolean("pinned", false),
        strongAlertAt = optLong("strongAlertAt", 0),
        apiBaseUrl = optString("apiBaseUrl", ""),
        apiModel = optString("apiModel", ""),
        vision = optBoolean("vision", true),
        nickname = optString("nickname", ""),
        identity = optString("identity", ""),
        background = optString("background", ""),
        catchphrase = optString("catchphrase", ""),
        memeLevel = optInt("memeLevel", 50)
    )

    private fun MessageEntity.toJson() = JSONObject()
        .put("id", id)
        .put("characterId", characterId)
        .put("role", role)
        .put("content", content)
        .put("createdAt", createdAt)
        .put("proactive", proactive)
        .put("imagePath", imagePath)
        .put("imageCaption", imageCaption)
        .put("visibleAt", visibleAt)

    private fun JSONObject.toMessage() = MessageEntity(
        id = optLong("id", 0),
        characterId = getString("characterId"),
        role = optString("role", "user"),
        content = optString("content", ""),
        createdAt = optLong("createdAt", 0),
        proactive = optBoolean("proactive", false),
        imagePath = optString("imagePath", ""),
        imageCaption = optString("imageCaption", ""),
        visibleAt = optLong("visibleAt", 0)
    )

    private fun MemoryEntity.toJson() = JSONObject()
        .put("id", id)
        .put("characterId", characterId)
        .put("content", content)
        .put("importance", importance)
        .put("tags", tags)
        .put("weight", weight.toDouble())
        .put("tier", tier)
        .put("pinned", pinned)
        .put("createdAt", createdAt)
        .put("lastRecalledAt", lastRecalledAt)

    private fun JSONObject.toMemory() = MemoryEntity(
        id = optLong("id", 0),
        characterId = getString("characterId"),
        content = optString("content", ""),
        importance = optInt("importance", 3),
        tags = optString("tags", ""),
        weight = optDouble("weight", 3.0).toFloat(),
        tier = optString("tier", "ACTIVE"),
        pinned = optBoolean("pinned", false),
        createdAt = optLong("createdAt", 0),
        lastRecalledAt = optLong("lastRecalledAt", 0)
    )
}
