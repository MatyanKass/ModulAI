package com.modulai.engine.llamacpp

import android.content.Context
import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.Effort
import com.modulai.core.runtime.Engine
import com.modulai.core.runtime.GenerationException
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import com.modulai.core.runtime.PromptTooLongException
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextEvent
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.ToolSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class LlamaCppEngine(context: Context) : Engine {
    override val id = "llama.cpp"
    override val capabilities = setOf(Capability.TEXT_GENERATION)

    /**
     * CPU always; GPU only when the OpenCL backend loaded and accepted a device (an Adreno
     * GPU with an OpenCL 3.0 driver) and has not crashed the app before. The first read loads
     * the native libraries and asks the GPU driver for its devices, which takes well under a
     * second but should not be done on the main thread; later reads are free.
     */
    override val backends: Set<Backend>
        get() = available ?: synchronized(prepareLock) { prepare() }

    /** GPUs llama.cpp can use, as "name (memory)". Empty until [backends] or [load] has run. */
    @Volatile var gpuDevices: List<String> = emptyList()
        private set

    /** Why [backends] has no GPU, once that is known; null while the GPU is usable. */
    @Volatile var gpuUnavailableReason: String? = null
        private set

    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private val kernelCacheDir = File(context.cacheDir, "opencl-kernels").path
    private val gpuGuard = GpuCrashGuard(
        File(context.noBackupFilesDir, "llama-gpu.pending"),
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString(),
    )

    // prepare() runs once, on whichever thread asks first; reads of `backends` after that
    // never wait, even while the llama thread is busy loading or generating.
    private val prepareLock = Any()
    @Volatile private var available: Set<Backend>? = null
    private var initialized = false

    // llama.cpp contexts are not thread-safe; every native call goes through this thread.
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "llama").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    override fun supports(model: ModelDescriptor) = model.format == ModelFormat.GGUF

    /**
     * Forgets that the GPU backend once took the process down and probes it again; returns the
     * new [backends]. Meant for an explicit user action ("try the GPU again"), because the
     * crash marker is also left behind when the app is killed in the middle of a GPU load,
     * e.g. swiped away from recents or killed for memory.
     */
    suspend fun retryGpu(): Set<Backend> = withContext(dispatcher) {
        synchronized(prepareLock) {
            gpuGuard.reset()
            available = null
            prepare()
        }
    }

    /**
     * Loads on exactly [LoadConfig.backend]. [Backend.CPU] keeps the GPU out entirely; [Backend.GPU]
     * offloads [LoadConfig.gpuLayers] layers (0 behaves like CPU). There is no silent fallback: a
     * GPU load that fails throws [GenerationException] carrying llama.cpp's own error and leaves the
     * engine usable, and retrying on [Backend.CPU] is the caller's decision.
     */
    override suspend fun load(model: ModelDescriptor, config: LoadConfig): TextSession = withContext(dispatcher) {
        val usable = synchronized(prepareLock) { prepare() }
        if (LlamaNative.backendCount() == 0) {
            throw GenerationException("No compute backend was found in $nativeLibDir")
        }
        if (config.backend !in usable) {
            val reason = gpuUnavailableReason.takeIf { config.backend == Backend.GPU } ?: "not supported by llama.cpp"
            throw GenerationException("Cannot run ${model.id} on ${config.backend}: $reason")
        }
        val gpuLayers = if (config.backend == Backend.GPU) config.gpuLayers else 0
        val backend = if (gpuLayers != 0) Backend.GPU else Backend.CPU
        val nativeLoad = {
            LlamaNative.load(
                model.path, config.contextTokens, config.threads, gpuLayers, config.lockMemory, config.useMmap,
            )
        }
        val handle = if (backend == Backend.CPU) nativeLoad() else try {
            // The first GPU load compiles the kernels and uploads the weights inside the driver.
            gpuGuard.run(nativeLoad)
        } catch (e: IOException) {
            throw GenerationException("Cannot run ${model.id} on GPU: the crash marker could not be written ($e)")
        }
        if (handle == 0L) {
            val reason = LlamaNative.lastError().ifBlank { "unknown error" }
            throw GenerationException("Could not load ${model.id} on $backend: $reason")
        }
        LlamaSession(handle, model, backend, dispatcher)
    }

    /** Must hold [prepareLock]. */
    private fun prepare(): Set<Backend> {
        available?.let { return it }
        if (!initialized) {
            LlamaNative.init(nativeLibDir) // CPU backends only, see CMakeLists.txt
            initialized = true
        }
        var devices = emptyList<String>()
        gpuUnavailableReason = if (gpuGuard.tripped) {
            "the GPU backend crashed the app during an earlier GPU start and was switched off"
        } else try {
            devices = gpuGuard.run { LlamaNative.loadGpuBackend(nativeLibDir, kernelCacheDir) }.toList()
            if (devices.isNotEmpty()) null else {
                LlamaNative.lastError().ifBlank { "no supported OpenCL GPU was found (only Adreno is supported)" }
            }
        } catch (e: IOException) {
            // Without the marker a driver crash would repeat on every start, so no GPU.
            "the GPU crash marker could not be written ($e)"
        }
        gpuDevices = devices
        val result = if (devices.isEmpty()) setOf(Backend.CPU) else setOf(Backend.GPU, Backend.CPU)
        available = result
        return result
    }
}

private class LlamaSession(
    private var handle: Long,
    override val model: ModelDescriptor,
    override val backend: Backend,
    private val dispatcher: CoroutineDispatcher,
) : TextSession {
    override val contextTokens = LlamaNative.contextSize(handle)

    override suspend fun countTokens(
        messages: List<ChatMessage>,
        effort: Effort,
        tools: List<ToolSpec>,
    ): Int = withContext(dispatcher) {
        val n = LlamaNative.countTokens(handle, messages.roles(), messages.contents(), tools.flat(), effort.thinking)
        if (n < 0) throw GenerationException("Chat template failed")
        n
    }

    override fun generate(
        messages: List<ChatMessage>,
        params: SamplingParams,
        tools: List<ToolSpec>,
    ): Flow<TextEvent> = callbackFlow {
        val job = launch(dispatcher) {
            val code = LlamaNative.generate(
                handle, messages.roles(), messages.contents(), tools.flat(),
                params.temperature, params.topP, params.topK, params.minP, params.repeatPenalty,
                params.maxTokens, params.seed, params.effort.thinking, params.effort.thinkingBudget,
                object : PieceCallback {
                    override fun onPiece(utf8: ByteArray) {
                        trySend(TextEvent.Piece(String(utf8, Charsets.UTF_8)))
                    }

                    override fun onToolCall(name: ByteArray, argumentsJson: ByteArray) {
                        trySend(TextEvent.ToolCall(String(name, Charsets.UTF_8), String(argumentsJson, Charsets.UTF_8)))
                    }
                },
            )
            when (code) {
                LlamaNative.STOP_EOS -> trySend(TextEvent.Done(StopReason.END))
                LlamaNative.STOP_LENGTH -> trySend(TextEvent.Done(StopReason.LENGTH))
                LlamaNative.STOP_CANCELLED -> trySend(TextEvent.Done(StopReason.CANCELLED))
                LlamaNative.ERR_PROMPT_TOO_LONG -> throw PromptTooLongException()
                LlamaNative.ERR_TEMPLATE -> throw GenerationException("Chat template failed")
                else -> throw GenerationException("Inference failed ($code)")
            }
        }
        job.invokeOnCompletion { close(it) }
        // The native loop cannot be interrupted, so ask it to stop and let the job finish.
        awaitClose { if (handle != 0L) LlamaNative.cancel(handle) }
    }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED)

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0
        LlamaNative.cancel(h)
        // Freed on the llama thread so it waits for a running generation to return.
        dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) { LlamaNative.free(h) }
    }

    private fun List<ChatMessage>.roles() = Array(size) { this[it].role.wire }
    private fun List<ChatMessage>.contents() = Array(size) { this[it].content }

    /** The JNI side expects [name, description, schema, name, ...]. */
    private fun List<ToolSpec>.flat() = Array(size * 3) {
        val tool = this[it / 3]
        when (it % 3) {
            0 -> tool.name
            1 -> tool.description
            else -> tool.parametersSchema
        }
    }
}
