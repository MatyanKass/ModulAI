package com.modulai.app.image

import android.content.Context
import com.modulai.core.runtime.ImageRequest
import com.modulai.core.runtime.ImageSampler
import org.json.JSONArray
import org.json.JSONObject

/** [size] is the square side for SD 1.x / 2.x class models, [largeSize] for SDXL class ones. */
enum class ImagePreset(val size: Int, val largeSize: Int) {
    LOW(384, 768),
    MEDIUM(512, 1024),
    HIGH(640, 1024),
    CUSTOM(512, 1024),
}

/** Sampling values a request is built from, after presets and model profile are applied. */
data class SamplingChoice(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val sampler: ImageSampler,
)

data class ImageSettings(
    /** File names inside the store's directories; null means none chosen. */
    val checkpoint: String? = null,
    val upscaler: String? = null,
    val taesd: String? = null,
    val vae: String? = null,
    val loras: Set<String> = emptySet(),
    val loraWeight: Float = 1f,
    /** Null means: take it from the built-in list or guess from the file name. */
    val profile: ModelProfile? = null,
    val preset: ImagePreset = ImagePreset.MEDIUM,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7f,
    val sampler: ImageSampler = ImageSampler.AUTO,
    /** Negative means a new random seed for every picture. */
    val seed: Long = -1,
    val strength: Float = 0.6f,
    val threads: Int = defaultThreads(),
    val negativePrompt: String = "",
) {
    /** The sampling profile of the chosen checkpoint. */
    fun effectiveProfile(): ModelProfile = profile
        ?: IMAGE_CATALOG.firstOrNull { it.kind == ImageModelKind.CHECKPOINT && it.fileName == checkpoint }?.profile
        ?: ModelProfile.guess(checkpoint.orEmpty())

    /** What [preset] means for the chosen checkpoint; the custom preset uses the stored values. */
    fun sampling(preset: ImagePreset = this.preset): SamplingChoice {
        if (preset == ImagePreset.CUSTOM) return SamplingChoice(width, height, steps, cfgScale, sampler)
        val profile = effectiveProfile()
        val side = if (isLargeModel(checkpoint.orEmpty())) preset.largeSize else preset.size
        return SamplingChoice(side, side, profile.steps[preset.ordinal], profile.cfgScale, profile.sampler)
    }

    /** Switches to the custom preset, starting from what the current preset resolves to. */
    fun customized(): ImageSettings {
        if (preset == ImagePreset.CUSTOM) return this
        val s = sampling()
        return copy(
            preset = ImagePreset.CUSTOM, width = s.width, height = s.height, steps = s.steps,
            cfgScale = s.cfgScale, sampler = s.sampler,
        )
    }

    companion object {
        val SIZE_CHOICES = listOf(256, 384, 512, 640, 768, 896, 1024)
        const val MAX_THREADS = 8

        /** Same reasoning as for text: on a 2 + 6 core phone, 4 threads beat both 6 and 8. */
        fun defaultThreads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        private fun isLargeModel(fileName: String): Boolean {
            val name = fileName.lowercase()
            return "xl" in name || "ssd-1b" in name || "vega" in name
        }
    }
}

/** Persists [ImageSettings]; separate from the main settings store, which AppContainer owns. */
class ImagePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("image", Context.MODE_PRIVATE)

    fun load(): ImageSettings {
        val json = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ImageSettings()
        val d = ImageSettings()
        return ImageSettings(
            checkpoint = json.optString("checkpoint").ifEmpty { null },
            upscaler = json.optString("upscaler").ifEmpty { null },
            taesd = json.optString("taesd").ifEmpty { null },
            vae = json.optString("vae").ifEmpty { null },
            loras = json.optJSONArray("loras")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
                ?: emptySet(),
            loraWeight = json.optDouble("loraWeight", d.loraWeight.toDouble()).toFloat(),
            profile = ModelProfile.entries.find { it.name == json.optString("profile") },
            preset = ImagePreset.entries.find { it.name == json.optString("preset") } ?: d.preset,
            width = json.optInt("width", d.width).takeIf { it in ImageSettings.SIZE_CHOICES } ?: d.width,
            height = json.optInt("height", d.height).takeIf { it in ImageSettings.SIZE_CHOICES } ?: d.height,
            steps = json.optInt("steps", d.steps).coerceIn(1, ImageRequest.MAX_STEPS),
            cfgScale = json.optDouble("cfgScale", d.cfgScale.toDouble()).toFloat(),
            sampler = ImageSampler.entries.find { it.name == json.optString("sampler") } ?: d.sampler,
            seed = json.optLong("seed", d.seed),
            strength = json.optDouble("strength", d.strength.toDouble()).toFloat().coerceIn(0f, 1f),
            threads = json.optInt("threads", d.threads).coerceIn(1, ImageSettings.MAX_THREADS),
            negativePrompt = json.optString("negativePrompt"),
        )
    }

    fun save(s: ImageSettings) {
        val json = JSONObject()
            .put("checkpoint", s.checkpoint.orEmpty())
            .put("upscaler", s.upscaler.orEmpty())
            .put("taesd", s.taesd.orEmpty())
            .put("vae", s.vae.orEmpty())
            .put("loras", JSONArray(s.loras))
            .put("loraWeight", s.loraWeight.toDouble())
            .put("profile", s.profile?.name.orEmpty())
            .put("preset", s.preset.name)
            .put("width", s.width)
            .put("height", s.height)
            .put("steps", s.steps)
            .put("cfgScale", s.cfgScale.toDouble())
            .put("sampler", s.sampler.name)
            .put("seed", s.seed)
            .put("strength", s.strength.toDouble())
            .put("threads", s.threads)
            .put("negativePrompt", s.negativePrompt)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}
