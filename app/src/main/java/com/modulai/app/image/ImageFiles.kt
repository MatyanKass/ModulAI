package com.modulai.app.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.modulai.app.R
import com.modulai.core.runtime.ImageData
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ImageMode { TXT2IMG, IMG2IMG, UPSCALE }

/** One picture in the history: a PNG under filesDir with a JSON note next to it. */
data class ImageRecord(
    val file: File,
    val mode: ImageMode,
    val prompt: String,
    val width: Int,
    val height: Int,
    val seed: Long,
    val steps: Int,
    val model: String,
    val createdAt: Long,
)

class ImageHistory(private val dir: File) {
    /** Newest first. Pictures whose note is missing or unreadable are still listed. */
    fun list(): List<ImageRecord> =
        dir.listFiles { f -> f.isFile && f.extension == "png" }
            .orEmpty()
            .map(::read)
            .sortedByDescending { it.createdAt }

    fun add(image: ImageData, mode: ImageMode, prompt: String, seed: Long, steps: Int, model: String): ImageRecord {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val file = File(dir, "$now.png")
        ImageIo.writePng(image, file)
        val record = ImageRecord(file, mode, prompt, image.width, image.height, seed, steps, model, now)
        note(file).writeText(
            JSONObject()
                .put("mode", mode.name)
                .put("prompt", prompt)
                .put("width", image.width)
                .put("height", image.height)
                .put("seed", seed)
                .put("steps", steps)
                .put("model", model)
                .put("createdAt", now)
                .toString(),
        )
        return record
    }

    fun delete(record: ImageRecord) {
        record.file.delete()
        note(record.file).delete()
    }

    private fun read(file: File): ImageRecord {
        val json = runCatching { JSONObject(note(file).readText()) }.getOrDefault(JSONObject())
        return ImageRecord(
            file = file,
            mode = ImageMode.entries.find { it.name == json.optString("mode") } ?: ImageMode.TXT2IMG,
            prompt = json.optString("prompt"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            seed = json.optLong("seed", -1),
            steps = json.optInt("steps"),
            model = json.optString("model"),
            createdAt = json.optLong("createdAt", file.lastModified()),
        )
    }

    private fun note(png: File) = File(png.parentFile, png.nameWithoutExtension + ".json")
}

object ImageIo {
    fun toBitmap(image: ImageData): Bitmap =
        Bitmap.createBitmap(image.toArgb(), image.width, image.height, Bitmap.Config.ARGB_8888)

    fun toImageData(bitmap: Bitmap): ImageData {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ImageData.fromArgb(bitmap.width, bitmap.height, pixels)
    }

    fun writePng(image: ImageData, file: File) {
        val bitmap = toBitmap(image)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes [uri] upright (EXIF rotation applied), reduced while decoding so that it has
     * at most [maxPixels] pixels: a camera photo at full size would not fit in memory twice.
     */
    fun decode(context: Context, uri: Uri, maxPixels: Long): Bitmap = try {
        val source = if (uri.scheme == "file") {
            ImageDecoder.createSource(File(requireNotNull(uri.path)))
        } else {
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > maxPixels) {
                val factor = sqrt(maxPixels.toDouble() / pixels)
                decoder.setTargetSize(
                    max(1, (info.size.width * factor).toInt()),
                    max(1, (info.size.height * factor).toInt()),
                )
            }
        }
    } catch (e: IOException) {
        throw IOException(context.getString(R.string.img_err_decode), e)
    }

    /** Scales and centre-crops [source] to exactly [width] x [height]. */
    fun cover(source: Bitmap, width: Int, height: Int): ImageData {
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = max(width, (source.width * scale).roundToInt())
        val scaledHeight = max(height, (source.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val cropped = Bitmap.createBitmap(scaled, (scaledWidth - width) / 2, (scaledHeight - height) / 2, width, height)
        return toImageData(cropped).also {
            if (cropped !== scaled) cropped.recycle()
            if (scaled !== source) scaled.recycle()
        }
    }

    /** Copies a PNG into the shared Pictures collection; needs no permission on Android 10+. */
    fun saveToGallery(context: Context, png: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ModulAI-${png.nameWithoutExtension}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ModulAI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the picture")
        try {
            resolver.openOutputStream(uri)?.use { out -> png.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("MediaStore gave no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
