package com.modulai.app.image

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.modulai.app.R
import com.modulai.core.models.HfLink
import com.modulai.core.models.ModelStore
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.ImageSampler
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What a file under `image-models/` is for; each kind has its own subdirectory. */
enum class ImageModelKind(val dir: String, val extensions: Set<String>, @StringRes val label: Int) {
    CHECKPOINT("checkpoints", setOf("gguf", "safetensors", "ckpt"), R.string.img_kind_checkpoint),
    UPSCALER("upscalers", setOf("pth", "safetensors", "gguf"), R.string.img_kind_upscaler),
    TAESD("taesd", setOf("safetensors", "gguf"), R.string.img_kind_taesd),
    VAE("vae", setOf("safetensors", "gguf", "ckpt"), R.string.img_kind_vae),
    LORA("lora", setOf("safetensors", "gguf"), R.string.img_kind_lora),
}

/**
 * How a checkpoint wants to be sampled. Distilled models break when driven with the step
 * count and guidance of a regular one, so quality presets are resolved through this.
 */
enum class ModelProfile(
    @StringRes val label: Int,
    /** Steps for the low, medium and high presets. */
    val steps: List<Int>,
    val cfgScale: Float,
    val sampler: ImageSampler,
) {
    STANDARD(R.string.img_profile_standard, listOf(12, 20, 28), 7f, ImageSampler.EULER_A),
    LCM(R.string.img_profile_lcm, listOf(4, 6, 8), 1.5f, ImageSampler.LCM),
    TURBO(R.string.img_profile_turbo, listOf(1, 2, 4), 1f, ImageSampler.EULER_A),
    ONE_STEP(R.string.img_profile_one_step, listOf(1, 1, 1), 1f, ImageSampler.EULER_A);

    companion object {
        /** A guess from the file name, for models that did not come from the built-in list. */
        fun guess(fileName: String): ModelProfile {
            val name = fileName.lowercase()
            return when {
                "sdxs" in name -> ONE_STEP
                "turbo" in name || "lightning" in name || "hyper" in name -> TURBO
                "lcm" in name || "tcd" in name -> LCM
                else -> STANDARD
            }
        }
    }
}

/** A downloadable model the app knows how to run. Sizes are the exact file sizes on Hugging Face. */
data class ImageCatalogEntry(
    val kind: ImageModelKind,
    val repo: String,
    val path: String,
    /** Name of the file on the device. */
    val fileName: String,
    val sizeBytes: Long,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val profile: ModelProfile = ModelProfile.STANDARD,
) {
    val link get() = HfLink.File(repo, "main", path)
}

val IMAGE_CATALOG = listOf(
    ImageCatalogEntry(
        ImageModelKind.CHECKPOINT, "concedo/sdxs-512-tinySDdistilled-GGUF", "sdxs-512-tinySDdistilled_Q8_0.gguf",
        "sdxs-512-q8.gguf", 682_847_200, R.string.img_cat_sdxs, R.string.img_cat_sdxs_desc, ModelProfile.ONE_STEP,
    ),
    ImageCatalogEntry(
        ImageModelKind.CHECKPOINT, "Green-Sky/SD-Turbo-GGUF", "sd_turbo-f16-q8_0.gguf",
        "sd-turbo-q8.gguf", 2_023_745_376, R.string.img_cat_turbo, R.string.img_cat_turbo_desc, ModelProfile.TURBO,
    ),
    ImageCatalogEntry(
        ImageModelKind.CHECKPOINT, "Lykon/dreamshaper-8-lcm", "DreamShaper8_LCM.safetensors",
        "dreamshaper-8-lcm.safetensors", 2_133_804_992, R.string.img_cat_lcm, R.string.img_cat_lcm_desc,
        ModelProfile.LCM,
    ),
    ImageCatalogEntry(
        ImageModelKind.CHECKPOINT, "second-state/stable-diffusion-v1-5-GGUF",
        "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
        "sd-v1-5-q8.gguf", 1_763_578_176, R.string.img_cat_sd15, R.string.img_cat_sd15_desc, ModelProfile.STANDARD,
    ),
    ImageCatalogEntry(
        ImageModelKind.TAESD, "madebyollin/taesd", "diffusion_pytorch_model.safetensors",
        "taesd.safetensors", 9_793_292, R.string.img_cat_taesd, R.string.img_cat_taesd_desc,
    ),
    ImageCatalogEntry(
        ImageModelKind.UPSCALER, "leonelhs/realesrgan", "RealESRGAN_x4plus_anime_6B.pth",
        "RealESRGAN_x4plus_anime_6B.pth", 17_938_799, R.string.img_cat_esrgan_anime, R.string.img_cat_esrgan_anime_desc,
    ),
    ImageCatalogEntry(
        ImageModelKind.UPSCALER, "leonelhs/realesrgan", "RealESRGAN_x4plus.pth",
        "RealESRGAN_x4plus.pth", 67_040_989, R.string.img_cat_esrgan, R.string.img_cat_esrgan_desc,
    ),
)

/** Image models on disk, under the app's external files directory. */
class ImageModelStore(private val context: Context) {
    private val root: File = context.getExternalFilesDir("image-models") ?: File(context.filesDir, "image-models")

    fun dir(kind: ImageModelKind) = File(root, kind.dir).apply { mkdirs() }

    fun list(kind: ImageModelKind): List<File> =
        dir(kind).listFiles { f -> f.isFile && f.extension.lowercase() in kind.extensions }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

    fun fileFor(kind: ImageModelKind, name: String) = File(dir(kind), ModelStore.sanitize(name))

    /** The file called [name], if it is still there. */
    fun find(kind: ImageModelKind, name: String?): File? =
        name?.let { File(dir(kind), it) }?.takeIf { it.isFile }

    fun accepts(kind: ImageModelKind, fileName: String) =
        fileName.substringAfterLast('.', "").lowercase() in kind.extensions

    /** Copies a file picked through the system picker; sd.cpp needs a real path. */
    suspend fun import(uri: Uri, kind: ImageModelKind): File = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw IOException(context.getString(R.string.img_err_file_type, kind.extensions.joinToString()))
        if (!accepts(kind, name)) {
            throw IOException(context.getString(R.string.img_err_file_type, kind.extensions.joinToString()))
        }
        val target = fileFor(kind, name)
        val part = File(target.parentFile, target.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: throw IOException(context.getString(R.string.img_err_open, name))
            if (!part.renameTo(target)) throw IOException(context.getString(R.string.img_err_open, name))
        } finally {
            part.delete()
        }
        target
    }

    companion object {
        fun descriptor(file: File, kind: ImageModelKind) = ModelDescriptor(
            id = file.nameWithoutExtension,
            path = file.absolutePath,
            format = if (file.extension.equals("gguf", ignoreCase = true)) ModelFormat.GGUF else ModelFormat.SAFETENSORS,
            capability = if (kind == ImageModelKind.UPSCALER) Capability.IMAGE_UPSCALE else Capability.IMAGE_GENERATION,
            sizeBytes = file.length(),
        )
    }
}
