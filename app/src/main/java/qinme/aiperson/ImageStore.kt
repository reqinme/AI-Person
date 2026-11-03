package qinme.aiperson

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 图片统一存放在 filesDir/media，数据库只保存文件名，便于备份迁移。
 */
object ImageStore {
    private const val DIR = "media"

    enum class Kind(val maxSide: Int, val square: Boolean) {
        CHAT(1024, false),
        AVATAR(512, true),
        BACKGROUND(1440, false)
    }

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** 把相册/相机的图片压缩后存进私有目录，返回文件名。 */
    suspend fun save(context: Context, uri: Uri, kind: Kind): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (bounds.outWidth / (sample * 2) > kind.maxSide * 2 &&
                bounds.outHeight / (sample * 2) > kind.maxSide * 2
            ) {
                sample *= 2
            }

            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null

            val rotated = rotateByExif(decoded, bytes)
            val scaled = scaleTo(rotated, kind)
            val name = "${UUID.randomUUID()}.jpg"
            FileOutputStream(file(context, name)).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
            name
        }.getOrNull()
    }

    private fun rotateByExif(source: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleTo(source: Bitmap, kind: Kind): Bitmap {
        val side = maxOf(source.width, source.height)
        val ratio = if (side > kind.maxSide) kind.maxSide.toFloat() / side else 1f
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        var result = if (ratio < 1f) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else {
            source
        }
        if (kind.square) {
            val size = minOf(result.width, result.height)
            val x = (result.width - size) / 2
            val y = (result.height - size) / 2
            val cropped = Bitmap.createBitmap(result, x, y, size, size)
            if (cropped !== result) result = cropped
        }
        return result
    }

    fun bitmap(context: Context, name: String): Bitmap? {
        if (name.isBlank()) return null
        return runCatching { BitmapFactory.decodeFile(file(context, name).absolutePath) }.getOrNull()
    }

    /** 传给多模态接口的 base64（不含前缀）。 */
    fun base64(name: String): String? {
        if (name.isBlank()) return null
        return runCatching {
            val f = file(AppGraph.appContext, name)
            if (!f.exists()) return null
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }.getOrNull()
    }

    fun delete(context: Context, name: String) {
        if (name.isBlank()) return
        runCatching { file(context, name).delete() }
    }

    /** 删除角色时清理她的头像、背景和聊天图片。 */
    suspend fun deleteForCharacter(context: Context, character: CharacterEntity, images: List<String>) {
        withContext(Dispatchers.IO) {
            delete(context, character.avatarPath)
            delete(context, character.backgroundPath)
            images.forEach { delete(context, it) }
        }
    }
}
