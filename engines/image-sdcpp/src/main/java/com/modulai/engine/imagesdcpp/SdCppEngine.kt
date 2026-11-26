package com.modulai.engine.imagesdcpp

import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.GenerationException
import com.modulai.core.runtime.ImageComponents
import com.modulai.core.runtime.ImageData
import com.modulai.core.runtime.ImageEngine
import com.modulai.core.runtime.ImageEvent
import com.modulai.core.runtime.ImageRequest
import com.modulai.core.runtime.ImageSession
import com.modulai.core.runtime.ImageStage
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.Session
import com.modulai.core.runtime.UpscaleSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** stable-diffusion.cpp on the CPU: image generation and ESRGAN-style upscaling. */
class SdCppEngine : ImageEngine {
    override val id = "stable-diffusion.cpp"
    override val capabilities = setOf(Capability.IMAGE_GENERATION, Capability.IMAGE_UPSCALE)
    override val backends = setOf(Backend.CPU)

    // One thread for all native work: a phone has neither the cores nor the memory to run
    // two of these jobs at once, and sd.cpp contexts are not thread-safe.
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "sd").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private var initialized = false

    override fun supports(model: ModelDescriptor): Boolean {
        val extension = model.path.substringAfterLast('.', "").lowercase()
        return when (model.capability) {
            Capability.IMAGE_GENERATION -> extension in MODEL_EXTENSIONS
            Capability.IMAGE_UPSCALE -> extension in UPSCALER_EXTENSIONS
            else -> false
        }
    }

    override suspend fun load(model: ModelDescriptor, config: LoadConfig): Session =
        if (model.capability == Capability.IMAGE_UPSCALE) loadUpscaler(model, config) else loadImage(model, config)

    override suspend fun loadImage(
        model: ModelDescriptor,
        config: LoadConfig,
        components: ImageComponents,
    ): ImageSession = withContext(dispatcher) {
        prepare()
        listOfNotNull(model.path, components.vaePath, components.taesdPath).forEach(::requireFile)
        val handle = SdNative.load(
            model.path, components.vaePath.orEmpty(), components.taesdPath.orEmpty(), config.threads,
        )
        if (handle == 0L) throw GenerationException("Could not load ${model.id}: ${lastError()}")
        SdImageSession(handle, model, dispatcher)
    }

    override suspend fun loadUpscaler(model: ModelDescriptor, config: LoadConfig): UpscaleSession =
        withContext(dispatcher) {
            prepare()
            requireFile(model.path)
            val handle = SdNative.loadUpscaler(model.path, config.threads)
            if (handle == 0L) throw GenerationException("Could not load ${model.id}: ${lastError()}")
            SdUpscaleSession(handle, model, dispatcher)
        }

    /** sd.cpp reports a missing file in terms of its internals; say it plainly instead. */
    private fun requireFile(path: String) {
        if (!File(path).isFile) throw GenerationException("File not found: $path")
    }

    private fun prepare() {
        if (!initialized) {
            SdNative.init()
            initialized = true
        }
        if (!SdNative.cpuSupported()) {
            throw GenerationException("This processor is too old: ARMv8.2 with dot product and FP16 is required")
        }
    }

    companion object {
        val MODEL_EXTENSIONS = setOf("gguf", "safetensors", "ckpt")
        val UPSCALER_EXTENSIONS = setOf("pth", "safetensors", "gguf")
    }
}

internal fun lastError() = String(SdNative.lastError(), Charsets.UTF_8).trim().ifBlank { "unknown error" }

private fun stageOf(code: Int) = when (code) {
    SdNative.STAGE_LOADING -> ImageStage.LOADING
    SdNative.STAGE_SAMPLING -> ImageStage.SAMPLING
    else -> ImageStage.TILES
}

/**
 * Runs one native job as a flow. [work] gets a callback to hand to the native call and
 * returns the pixels with `[result code, width, height]`; [cancel] asks that call to stop.
 */
private fun nativeFlow(
    dispatcher: CoroutineDispatcher,
    seed: Long,
    cancel: () -> Unit,
    work: (ProgressCallback, IntArray) -> ByteArray?,
): Flow<ImageEvent> = callbackFlow {
    val stop = AtomicBoolean(false)
    val job = launch(dispatcher) { runNative(seed, stop, cancel, work) }
    job.invokeOnCompletion { close(it) }
    // The native call cannot be interrupted, so ask it to stop and let the job finish.
    awaitClose {
        stop.set(true)
        cancel()
    }
}.buffer(Channel.UNLIMITED)

private fun ProducerScope<ImageEvent>.runNative(
    seed: Long,
    stop: AtomicBoolean,
    cancel: () -> Unit,
    work: (ProgressCallback, IntArray) -> ByteArray?,
) {
    val out = IntArray(3)
    val pixels = work(
        object : ProgressCallback {
            override fun onProgress(stage: Int, done: Int, total: Int) {
                // A stop request that arrived before the native call began was dropped there.
                if (stop.get()) cancel()
                trySend(ImageEvent.Progress(stageOf(stage), done, total))
            }
        },
        out,
    )
    when {
        pixels != null && out[0] == SdNative.RESULT_OK ->
            trySend(ImageEvent.Done(ImageData(out[1], out[2], pixels), seed))
        out[0] == SdNative.RESULT_CANCELLED -> throw GenerationException("Stopped")
        else -> throw GenerationException(lastError())
    }
}

private class SdImageSession(
    private var handle: Long,
    override val model: ModelDescriptor,
    private val dispatcher: CoroutineDispatcher,
) : ImageSession {
    override val backend = Backend.CPU
    override val family: String = SdNative.modelVersion(handle)

    override fun generate(request: ImageRequest): Flow<ImageEvent> {
        val seed = if (request.seed < 0) Random.nextLong(0, Int.MAX_VALUE.toLong()) else request.seed
        val init = request.initImage
        return nativeFlow(dispatcher, seed, { if (handle != 0L) SdNative.cancel(handle) }) { callback, out ->
            request.problem()?.let { throw GenerationException(it) }
            if (handle == 0L) throw GenerationException("The model was unloaded")
            SdNative.generate(
                handle, request.prompt, request.negativePrompt, request.width, request.height, request.steps,
                request.cfgScale, request.sampler.wire, "", seed,
                init?.rgb, init?.width ?: 0, init?.height ?: 0, request.strength,
                Array(request.loras.size) { request.loras[it].path },
                FloatArray(request.loras.size) { request.loras[it].weight },
                request.vaeTiling, callback, out,
            )
        }
    }

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0
        SdNative.cancel(h)
        // Freed on the native thread so it waits for a running generation to return.
        dispatcher.dispatch(EmptyCoroutineContext) { SdNative.free(h) }
    }
}

private class SdUpscaleSession(
    private var handle: Long,
    override val model: ModelDescriptor,
    private val dispatcher: CoroutineDispatcher,
) : UpscaleSession {
    override val backend = Backend.CPU
    override val scale = SdNative.upscalerScale(handle)

    override fun upscale(image: ImageData): Flow<ImageEvent> =
        nativeFlow(dispatcher, 0, { if (handle != 0L) SdNative.cancelUpscaler(handle) }) { callback, out ->
            if (handle == 0L) throw GenerationException("The model was unloaded")
            SdNative.upscale(handle, image.rgb, image.width, image.height, callback, out)
        }

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0
        SdNative.cancelUpscaler(h)
        dispatcher.dispatch(EmptyCoroutineContext) { SdNative.freeUpscaler(h) }
    }
}
