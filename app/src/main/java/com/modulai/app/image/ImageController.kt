package com.modulai.app.image

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.modulai.app.R
import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.ImageComponents
import com.modulai.core.runtime.ImageData
import com.modulai.core.runtime.ImageEngine
import com.modulai.core.runtime.ImageEvent
import com.modulai.core.runtime.ImageRequest
import com.modulai.core.runtime.ImageSession
import com.modulai.core.runtime.ImageStage
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.Lora
import com.modulai.core.runtime.UpscaleSession
import com.modulai.engine.imagesdcpp.SdCppEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.ceil

enum class ImagePhase { LOADING, SAMPLING, DECODING, UPSCALING, SAVING }

/** A job in progress. [total] is 0 while the phase has nothing to count. */
data class ImageProgress(
    val phase: ImagePhase,
    val done: Int = 0,
    val total: Int = 0,
    /** SystemClock.elapsedRealtime() when the job began. */
    val startedAt: Long,
    /** Stop was asked for; the engine finishes its current step first. */
    val stopping: Boolean = false,
)

data class ImageUiState(
    val settings: ImageSettings,
    val progress: ImageProgress? = null,
    val error: String? = null,
    /** The picture shown in the result area. */
    val result: ImageRecord? = null,
    val history: List<ImageRecord> = emptyList(),
    /** Bumped when files in the model store may have changed. */
    val modelsRevision: Int = 0,
) {
    val busy get() = progress != null
}

/** Thrown for problems the user can fix; the message is already localized. */
class ImageUserException(message: String) : Exception(message)

/**
 * Owns the image engine, its loaded models, the settings and the history.
 *
 * Process-wide rather than owned by AppContainer: a generation takes minutes and must
 * survive the activity being recreated, and the chat tool has to reach the same models
 * as the image screen. Public functions are called on the main thread.
 */
class ImageController private constructor(private val context: Context) {
    val store = ImageModelStore(context)
    private val prefs = ImagePrefs(context)
    private val history = ImageHistory(File(context.filesDir, "images"))
    private val engine: ImageEngine = SdCppEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ImageUiState(prefs.load()))
    val state: StateFlow<ImageUiState> = _state

    private data class LoadKey(val model: String, val vae: String?, val taesd: String?, val threads: Int)

    private var session: ImageSession? = null
    private var sessionKey: LoadKey? = null
    private var upscaler: UpscaleSession? = null
    private var upscalerKey: LoadKey? = null
    private var job: Job? = null

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { history.list() }
            _state.update { it.copy(history = saved, result = it.result ?: saved.firstOrNull()) }
        }
    }

    fun update(settings: ImageSettings) {
        prefs.save(settings)
        _state.update { it.copy(settings = settings) }
    }

    /** Call after a download or import finished, or a model file was deleted. */
    fun modelsChanged() = _state.update { it.copy(modelsRevision = it.modelsRevision + 1) }

    /** True when a checkpoint is chosen and still on disk, i.e. [generateForTool] can work. */
    fun canGenerate() = store.find(ImageModelKind.CHECKPOINT, _state.value.settings.checkpoint) != null

    fun dismissError() = _state.update { it.copy(error = null) }

    fun show(record: ImageRecord) = _state.update { it.copy(result = record) }

    /** Text-to-image, or image-to-image when [source] is given. */
    fun generate(prompt: String, source: Uri?) = start {
        val settings = _state.value.settings
        generateImage(prompt, settings.negativePrompt, settings.sampling(), settings.seed, source)
    }

    fun upscale(source: Uri) = start { upscaleImage(source) }

    /** Asks the running job to stop; it ends after the step or block in progress. */
    fun stop() {
        if (job?.isActive != true) return
        _state.update { it.copy(progress = it.progress?.copy(stopping = true)) }
        job?.cancel()
    }

    fun delete(record: ImageRecord) {
        scope.launch {
            withContext(Dispatchers.IO) { history.delete(record) }
            _state.update { s ->
                val rest = s.history.filterNot { it.file == record.file }
                s.copy(history = rest, result = if (s.result?.file == record.file) rest.firstOrNull() else s.result)
            }
        }
    }

    fun deleteModel(file: File) {
        if (file.absolutePath == sessionKey?.model || file.absolutePath == upscalerKey?.model) unload()
        file.delete()
        modelsChanged()
    }

    /** Frees the memory held by loaded models; they load again on the next use. */
    fun unload() {
        session?.close()
        session = null
        sessionKey = null
        upscaler?.close()
        upscaler = null
        upscalerKey = null
    }

    /**
     * Generates one picture with the chosen checkpoint and the medium preset, for callers
     * outside the image screen. Fails with [ImageUserException] when no model is chosen,
     * another job is running or the user stopped it, and with the engine's exceptions otherwise.
     */
    suspend fun generateForTool(prompt: String, negativePrompt: String?): ImageRecord =
        withContext(Dispatchers.Main.immediate) {
            if (_state.value.busy) throw ImageUserException(context.getString(R.string.img_err_busy))
            val settings = _state.value.settings
            begin(ImagePhase.LOADING)
            // Runs in the controller's scope so the Stop button of the image screen reaches it too.
            val task = scope.async {
                try {
                    val negative = negativePrompt ?: settings.negativePrompt
                    generateImage(prompt, negative, settings.sampling(ImagePreset.MEDIUM), -1, null)
                } finally {
                    _state.update { it.copy(progress = null) }
                }
            }
            job = task
            try {
                task.await()
            } catch (e: CancellationException) {
                if (task.isCancelled && isActive) throw ImageUserException(context.getString(R.string.img_err_stopped))
                task.cancel()
                throw e
            }
        }

    private fun start(work: suspend () -> ImageRecord) {
        if (_state.value.busy) return
        begin(ImagePhase.LOADING)
        job = scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(progress = null) }
            }
        }
    }

    private fun begin(phase: ImagePhase) = _state.update {
        it.copy(progress = ImageProgress(phase, startedAt = SystemClock.elapsedRealtime()), error = null)
    }

    private fun phase(phase: ImagePhase, done: Int = 0, total: Int = 0) = _state.update {
        // A late progress event must not hide that a stop is under way.
        it.copy(progress = it.progress?.copy(phase = phase, done = done, total = total))
    }

    private suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        sampling: SamplingChoice,
        seed: Long,
        source: Uri?,
    ): ImageRecord {
        if (prompt.isBlank()) throw ImageUserException(context.getString(R.string.img_err_no_prompt))
        val settings = _state.value.settings
        val model = store.find(ImageModelKind.CHECKPOINT, settings.checkpoint)
            ?: throw ImageUserException(context.getString(R.string.img_err_no_model))
        val init = source?.let {
            withContext(Dispatchers.Default) {
                val bitmap = ImageIo.decode(context, it, MAX_SOURCE_PIXELS)
                try {
                    ImageIo.cover(bitmap, sampling.width, sampling.height)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val request = ImageRequest(
            prompt = prompt.trim(),
            negativePrompt = negativePrompt.trim(),
            width = sampling.width,
            height = sampling.height,
            steps = if (init == null) sampling.steps else stepsForEdit(sampling.steps, settings.strength),
            cfgScale = sampling.cfgScale,
            sampler = sampling.sampler,
            seed = seed,
            initImage = init,
            strength = settings.strength,
            loras = settings.loras.mapNotNull { store.find(ImageModelKind.LORA, it) }
                .map { Lora(it.absolutePath, settings.loraWeight) },
            vaeTiling = sampling.width * sampling.height > UNTILED_PIXELS,
        )
        val done = collect(imageSession(model, settings).generate(request))
        return save(
            done.image, if (init == null) ImageMode.TXT2IMG else ImageMode.IMG2IMG, request.prompt, done.seed,
            request.steps, model.nameWithoutExtension,
        )
    }

    private suspend fun upscaleImage(source: Uri): ImageRecord {
        val settings = _state.value.settings
        val model = store.find(ImageModelKind.UPSCALER, settings.upscaler)
            ?: throw ImageUserException(context.getString(R.string.img_err_no_upscaler))
        val session = upscaleSession(model, settings)
        val input = withContext(Dispatchers.Default) {
            val bitmap = ImageIo.decode(context, source, MAX_UPSCALED_PIXELS / (session.scale * session.scale))
            try {
                ImageIo.toImageData(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        val done = collect(session.upscale(input))
        return save(done.image, ImageMode.UPSCALE, "", -1, 0, model.nameWithoutExtension)
    }

    /** The upscaler chosen in the image settings, loaded if needed; null when none is installed. */
    suspend fun frameUpscaler(): UpscaleSession? {
        val settings = _state.value.settings
        val model = store.find(ImageModelKind.UPSCALER, settings.upscaler) ?: return null
        return upscaleSession(model, settings)
    }

    private suspend fun collect(events: Flow<ImageEvent>): ImageEvent.Done {
        var result: ImageEvent.Done? = null
        events.collect { event ->
            when (event) {
                is ImageEvent.Progress -> when {
                    event.stage == ImageStage.LOADING -> phase(ImagePhase.LOADING, event.done, event.total)
                    event.stage == ImageStage.TILES -> phase(ImagePhase.UPSCALING, event.done, event.total)
                    // After the last step only the VAE is left, and it reports nothing.
                    event.done >= event.total -> phase(ImagePhase.DECODING)
                    else -> phase(ImagePhase.SAMPLING, event.done, event.total)
                }
                is ImageEvent.Done -> result = event
            }
        }
        return result ?: throw IOException(context.getString(R.string.img_err_no_result))
    }

    private suspend fun save(
        image: ImageData, mode: ImageMode, prompt: String, seed: Long, steps: Int, model: String,
    ): ImageRecord {
        phase(ImagePhase.SAVING)
        // The picture took minutes to make: a stop that arrives now must not lose it.
        return withContext(NonCancellable) {
            val record = withContext(Dispatchers.Default) { history.add(image, mode, prompt, seed, steps, model) }
            _state.update { it.copy(result = record, history = listOf(record) + it.history) }
            record
        }
    }

    private suspend fun imageSession(model: File, settings: ImageSettings): ImageSession {
        val vae = store.find(ImageModelKind.VAE, settings.vae)?.absolutePath
        val taesd = store.find(ImageModelKind.TAESD, settings.taesd)?.absolutePath
        val key = LoadKey(model.absolutePath, vae, taesd, settings.threads)
        session?.takeIf { sessionKey == key }?.let { return it }
        session?.close()
        session = null
        sessionKey = null
        phase(ImagePhase.LOADING)
        val loaded = engine.loadImage(
            ImageModelStore.descriptor(model, ImageModelKind.CHECKPOINT),
            LoadConfig(Backend.CPU, threads = settings.threads),
            ImageComponents(vae, taesd),
        )
        session = loaded
        sessionKey = key
        return loaded
    }

    private suspend fun upscaleSession(model: File, settings: ImageSettings): UpscaleSession {
        val key = LoadKey(model.absolutePath, null, null, settings.threads)
        upscaler?.takeIf { upscalerKey == key }?.let { return it }
        upscaler?.close()
        upscaler = null
        upscalerKey = null
        phase(ImagePhase.LOADING)
        val loaded = engine.loadUpscaler(
            ImageModelStore.descriptor(model, ImageModelKind.UPSCALER),
            LoadConfig(Backend.CPU, threads = settings.threads),
        )
        upscaler = loaded
        upscalerKey = key
        return loaded
    }

    companion object {
        /**
         * Image-to-image only runs the last `strength` share of the schedule, which for a
         * model meant for 1-4 steps would leave a single step from pure noise. The schedule
         * is stretched so that about [steps] of them really run.
         */
        fun stepsForEdit(steps: Int, strength: Float): Int =
            if (strength <= 0f) steps else ceil(steps / strength).toInt().coerceIn(steps, ImageRequest.MAX_STEPS)

        /** Largest picture the VAE decodes in one piece; bigger ones are decoded in tiles. */
        private const val UNTILED_PIXELS = 640 * 640

        /** A source for image-to-image is cropped to the output size anyway. */
        private const val MAX_SOURCE_PIXELS = 4_000_000L

        /**
         * Upper bound for an enlarged picture. It has to exist as bytes, as pixels and as
         * a bitmap at the same time while it is saved, about 11 bytes per pixel in total.
         */
        const val MAX_UPSCALED_PIXELS = 16_000_000L

        // Holds the application context only, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ImageController? = null

        fun get(context: Context): ImageController =
            instance ?: synchronized(this) {
                instance ?: ImageController(context.applicationContext).also { instance = it }
            }
    }
}
