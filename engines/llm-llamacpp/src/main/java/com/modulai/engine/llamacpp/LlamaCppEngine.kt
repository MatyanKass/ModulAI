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
import java.util.concurrent.Executors

class LlamaCppEngine(context: Context) : Engine {
    override val id = "llama.cpp"
    override val capabilities = setOf(Capability.TEXT_GENERATION)

    // GPU (OpenCL) is not compiled in yet, so only CPU is advertised.
    override val backends = setOf(Backend.CPU)

    private val nativeLibDir = context.applicationInfo.nativeLibraryDir

    // llama.cpp contexts are not thread-safe; every native call goes through this thread.
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "llama").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private var initialized = false

    override fun supports(model: ModelDescriptor) = model.format == ModelFormat.GGUF

    override suspend fun load(model: ModelDescriptor, config: LoadConfig): TextSession = withContext(dispatcher) {
        if (!initialized) {
            LlamaNative.init(nativeLibDir)
            initialized = true
        }
        if (LlamaNative.backendCount() == 0) {
            throw GenerationException("No compute backend was found in $nativeLibDir")
        }
        val handle = LlamaNative.load(
            model.path, config.contextTokens, config.threads, 0, config.lockMemory, config.useMmap,
        )
        if (handle == 0L) {
            val reason = LlamaNative.lastError().ifBlank { "unknown error" }
            throw GenerationException("Could not load ${model.id}: $reason")
        }
        LlamaSession(handle, model, dispatcher)
    }
}

private class LlamaSession(
    private var handle: Long,
    override val model: ModelDescriptor,
    private val dispatcher: CoroutineDispatcher,
) : TextSession {
    override val backend = Backend.CPU
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
