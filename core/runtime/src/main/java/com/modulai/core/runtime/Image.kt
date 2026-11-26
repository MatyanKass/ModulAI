package com.modulai.core.runtime

import kotlinx.coroutines.flow.Flow

/** An image as tightly packed RGB bytes, three per pixel, rows top to bottom. */
class ImageData(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(width > 0 && height > 0) { "Image size must be positive, got ${width}x$height" }
        require(rgb.size == width * height * 3) { "Expected ${width * height * 3} bytes, got ${rgb.size}" }
    }

    /** Pixels as opaque 0xAARRGGBB integers, the layout Android bitmaps use. */
    fun toArgb(): IntArray = IntArray(width * height) {
        val i = it * 3
        (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or ((rgb[i + 1].toInt() and 0xFF) shl 8) or
            (rgb[i + 2].toInt() and 0xFF)
    }

    companion object {
        /** Builds an image from 0xAARRGGBB pixels; transparency is flattened onto white. */
        fun fromArgb(width: Int, height: Int, pixels: IntArray): ImageData {
            require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
            val rgb = ByteArray(pixels.size * 3)
            for (p in pixels.indices) {
                val c = pixels[p]
                val alpha = c ushr 24
                for (channel in 0..2) {
                    val v = (c ushr (16 - channel * 8)) and 0xFF
                    rgb[p * 3 + channel] = ((v * alpha + 255 * (255 - alpha)) / 255).toByte()
                }
            }
            return ImageData(width, height, rgb)
        }
    }
}

/** How each denoising step updates the picture. [wire] is the name the engine understands. */
enum class ImageSampler(val wire: String) {
    /** Whatever suits the loaded model. */
    AUTO(""),
    EULER_A("euler_a"),
    EULER("euler"),
    HEUN("heun"),
    DPMPP_2M("dpm++2m"),
    DPMPP_2S_A("dpm++2s_a"),
    DDIM_TRAILING("ddim_trailing"),
    LCM("lcm"),
    TCD("tcd"),
}

/** A LoRA file applied on top of the model for one request. */
data class Lora(val path: String, val weight: Float = 1f)

data class ImageRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    /** How strongly the prompt is followed; distilled (turbo, LCM) models want about 1. */
    val cfgScale: Float = 7f,
    val sampler: ImageSampler = ImageSampler.AUTO,
    /** Negative picks a random seed, reported back in [ImageEvent.Done]. */
    val seed: Long = -1,
    /** Starting picture for image-to-image; any size, it is fitted to [width] x [height]. */
    val initImage: ImageData? = null,
    /** Image-to-image only: 0 keeps the source, 1 ignores it. */
    val strength: Float = 0.75f,
    val loras: List<Lora> = emptyList(),
    /**
     * Decode the finished picture in overlapping tiles. Slower, but the memory the VAE
     * needs stops growing with the picture: at 1024x1024 it would otherwise take several GB.
     */
    val vaeTiling: Boolean = false,
) {
    /** A description of what is wrong with the request, or null when it can be run. */
    fun problem(): String? = when {
        width !in SIZE_RANGE || height !in SIZE_RANGE -> "Width and height must be between $MIN_SIZE and $MAX_SIZE"
        width % SIZE_STEP != 0 || height % SIZE_STEP != 0 -> "Width and height must be multiples of $SIZE_STEP"
        steps !in 1..MAX_STEPS -> "Steps must be between 1 and $MAX_STEPS"
        !cfgScale.isFinite() || cfgScale < 0f || cfgScale > 30f -> "CFG scale must be between 0 and 30"
        !strength.isFinite() || strength < 0f || strength > 1f -> "Strength must be between 0 and 1"
        else -> null
    }

    companion object {
        const val MIN_SIZE = 128
        const val MAX_SIZE = 2048
        const val MAX_STEPS = 150

        /** Latents are 1/8 of the picture and the network halves them three times. */
        const val SIZE_STEP = 64
        private val SIZE_RANGE = MIN_SIZE..MAX_SIZE
    }
}

enum class ImageStage {
    /** Reading weights; counted in tensors. */
    LOADING,

    /** Denoising; counted in steps. */
    SAMPLING,

    /** Upscaling; counted in blocks of the source image. */
    TILES,
}

sealed interface ImageEvent {
    data class Progress(val stage: ImageStage, val done: Int, val total: Int) : ImageEvent

    /** Always the last event. [seed] is the seed actually used (meaningless for upscaling). */
    class Done(val image: ImageData, val seed: Long) : ImageEvent
}

/** Extra files a diffusion model can be loaded with. */
data class ImageComponents(
    /** Replaces the VAE built into the checkpoint. */
    val vaePath: String? = null,
    /** Tiny autoencoder used instead of the VAE: much faster and lighter, slightly softer. */
    val taesdPath: String? = null,
)

interface ImageSession : Session {
    /** Model family as the engine detected it, e.g. "SD 1.x". */
    val family: String

    /**
     * Runs text-to-image, or image-to-image when [ImageRequest.initImage] is set.
     * Cancelling the collector stops generation after the denoising step in progress.
     * Fails with [GenerationException].
     */
    fun generate(request: ImageRequest): Flow<ImageEvent>
}

interface UpscaleSession : Session {
    /** How many times each side grows. */
    val scale: Int

    /**
     * Enlarges [image] block by block, so memory use does not grow with the picture.
     * Cancelling the collector stops after the block in progress.
     */
    fun upscale(image: ImageData): Flow<ImageEvent>
}

/** An [Engine] for pictures. [Engine.load] returns one of these sessions by model capability. */
interface ImageEngine : Engine {
    suspend fun loadImage(
        model: ModelDescriptor,
        config: LoadConfig,
        components: ImageComponents = ImageComponents(),
    ): ImageSession

    suspend fun loadUpscaler(model: ModelDescriptor, config: LoadConfig): UpscaleSession
}
