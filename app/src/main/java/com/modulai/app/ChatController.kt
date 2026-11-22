package com.modulai.app

import com.modulai.core.runtime.ChatMessage
import com.modulai.core.runtime.LoadConfig
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.PromptTooLongException
import com.modulai.core.runtime.Role
import com.modulai.core.runtime.SamplingParams
import com.modulai.core.runtime.StopReason
import com.modulai.core.runtime.TextEvent
import com.modulai.core.runtime.TextSession
import com.modulai.core.runtime.fitToContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val model: ModelDescriptor? = null,
    val loading: Boolean = false,
    val generating: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val usedTokens: Int = 0,
    val contextTokens: Int = 0,
    /** Messages dropped from the prompt because the conversation outgrew the context. */
    val trimmed: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
)

/** Owns the loaded text model and the single in-memory conversation. */
class ChatController(private val container: AppContainer) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private var session: TextSession? = null
    private var loadedWith: LoadConfig? = null
    private var job: Job? = null

    fun select(model: ModelDescriptor?) {
        stop()
        unload()
        _state.update { it.copy(model = model, error = null, usedTokens = 0, contextTokens = 0) }
    }

    fun newChat() {
        stop()
        _state.update { it.copy(messages = emptyList(), error = null, usedTokens = 0, trimmed = 0, truncated = false) }
    }

    fun stop() {
        job?.cancel()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun send(text: String) {
        val model = _state.value.model ?: return
        if (_state.value.generating || _state.value.loading || text.isBlank()) return
        val history = _state.value.messages + ChatMessage(Role.USER, text.trim())
        _state.update { it.copy(messages = history, error = null, truncated = false) }

        job = container.scope.launch {
            try {
                val settings = container.runSettings.value
                val s = ensureLoaded(model, settings)
                val system = settings.systemPrompt.trim()
                val full = if (system.isEmpty()) history else listOf(ChatMessage(Role.SYSTEM, system)) + history
                val prompt = s.fitToContext(full, settings.effort)
                val promptTokens = s.countTokens(prompt, settings.effort)

                _state.update {
                    it.copy(
                        generating = true,
                        messages = history + ChatMessage(Role.ASSISTANT, ""),
                        trimmed = full.size - prompt.size,
                        usedTokens = promptTokens,
                        contextTokens = s.contextTokens,
                        tokensPerSecond = 0f,
                    )
                }

                val reply = StringBuilder()
                var pieces = 0
                var firstPieceAt = 0L
                val params = SamplingParams(temperature = settings.temperature, effort = settings.effort)
                s.generate(prompt, params).collect { event ->
                    when (event) {
                        is TextEvent.Piece -> {
                            val now = System.nanoTime()
                            if (pieces == 0) firstPieceAt = now
                            pieces++
                            reply.append(event.text)
                            val seconds = (now - firstPieceAt) / 1e9f
                            _state.update {
                                it.copy(
                                    messages = history + ChatMessage(Role.ASSISTANT, reply.toString()),
                                    usedTokens = promptTokens + pieces,
                                    tokensPerSecond = if (pieces > 1 && seconds > 0) (pieces - 1) / seconds else 0f,
                                )
                            }
                        }
                        is TextEvent.Done -> _state.update { it.copy(truncated = event.reason == StopReason.LENGTH) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PromptTooLongException) {
                _state.update { it.copy(error = ERROR_TOO_LONG) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { st ->
                    // An empty reply bubble is noise; drop it if nothing was generated.
                    val last = st.messages.lastOrNull()
                    val cleaned = if (last?.role == Role.ASSISTANT && last.content.isEmpty()) st.messages.dropLast(1) else st.messages
                    st.copy(generating = false, loading = false, messages = cleaned)
                }
            }
        }
    }

    private suspend fun ensureLoaded(model: ModelDescriptor, settings: RunSettings): TextSession {
        val choice = container.engines.resolve(model, container.device.inspect().backends)
            ?: error("No engine can run ${model.id}")
        val config = LoadConfig(
            backend = choice.backend,
            contextTokens = settings.contextTokens,
            threads = settings.threads,
            lockMemory = settings.lockMemory,
            useMmap = settings.useMmap,
        )
        session?.let { if (loadedWith == config && it.model == model) return it }
        unload()
        _state.update { it.copy(loading = true) }
        try {
            val s = choice.engine.load(model, config) as TextSession
            session = s
            loadedWith = config
            return s
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private fun unload() {
        session?.close()
        session = null
        loadedWith = null
    }

    companion object {
        /** Marker replaced by a localized string in the UI. */
        const val ERROR_TOO_LONG = "\u0000too_long"
    }
}
