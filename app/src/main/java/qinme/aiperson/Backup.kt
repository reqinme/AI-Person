package qinme.aiperson

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份是一个 zip：backup.json + media/ 下的图片。
 * 不含 API Key，只留接口地址和模型名；导入后需要重新填 Key。
 */
object Backup {

    suspend fun export(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppGraph.db
            val characters = db.characterDao().all()
            val messages = characters.flatMap { db.messageDao().all(it.id) }
            val memories = db.memoryDao().all()
            val relationships = db.relationshipDao().all()
            val personalities = db.personalityDao().all()
            val moods = characters.mapNotNull { db.moodDao().get(it.id) }

            val root = JSONObject()
                .put("version", 2)
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
                val file = ImageStore.file(context, name)
                if (!file.exists()) return@forEach
                zout.putNextEntry(ZipEntry("media/$name"))
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
                PersonalityEntity(
                    characterId = it.getString("characterId"),
                    warmth = it.optInt("warmth", 50),
                    liveliness = it.optInt("liveliness", 50),
                    initiative = it.optInt("initiative", 40),
                    independence = it.optInt("independence", 50),
                    clinginess = it.optInt("clinginess", 30),
                    prefWarmth = it.optInt("prefWarmth", 50),
                    prefLiveliness = it.optInt("prefLiveliness", 50),
                    prefInitiative = it.optInt("prefInitiative", 40),
                    prefIndependence = it.optInt("prefIndependence", 50),
                    prefClinginess = it.optInt("prefClinginess", 30)
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
            db.messageDao().clear()
            db.memoryDao().clear()
            db.relationshipDao().clear()
            db.personalityDao().clear()
            db.moodDao().clear()
            db.characterDao().clear()

            characters.forEach { db.characterDao().upsert(it) }
            messages.forEach { db.messageDao().insert(it) }
            memories.forEach { db.memoryDao().insert(it) }
            relationships.forEach { db.relationshipDao().upsert(it) }
            personalities.forEach { db.personalityDao().upsert(it) }
            moods.forEach { db.moodDao().upsert(it) }

            // 数据落库后再解压图片；缺失的图片不影响文字内容。
            extractMedia(context, uri)

            characters.size
        }
    }

    private fun extractMedia(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        if (entry.name.startsWith("media/") && !entry.isDirectory) {
                            val name = entry.name.removePrefix("media/")
                            ImageStore.file(context, name).outputStream()
                                .use { out -> zin.copyTo(out) }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
        }
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
        apiModel = optString("apiModel", "")
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
