package com.modulai.app

import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.ResearchProgress
import com.modulai.core.agent.ResearchReport
import com.modulai.core.agent.Researcher
import com.modulai.core.runtime.SamplingParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResearchState(
    val running: Boolean = false,
    val progress: ResearchProgress? = null,
    val report: ResearchReport? = null,
    val error: String? = null,
)

/** Standalone research runs. They use the model selected for chat. */
class ResearchController(private val container: AppContainer) {
    private val _state = MutableStateFlow(ResearchState())
    val state: StateFlow<ResearchState> = _state
    val log = AgentLog()
    private var job: Job? = null

    fun start(topic: String) {
        if (_state.value.running || topic.isBlank()) return
        log.clear()
        _state.value = ResearchState(running = true)
        job = container.scope.launch {
            try {
                val settings = container.runSettings.value
                val session = container.chat.session()
                val params = SamplingParams(temperature = settings.temperature, effort = settings.effort)
                val report = Researcher(session, container.web, log).run(topic.trim(), settings.researchDepth, params) { p ->
                    _state.update { it.copy(progress = p) }
                }
                _state.value = ResearchState(report = report)
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, progress = null) }
                throw e
            } catch (e: Exception) {
                _state.value = ResearchState(error = e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
