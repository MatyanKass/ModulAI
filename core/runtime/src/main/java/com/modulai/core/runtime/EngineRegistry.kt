package com.modulai.core.runtime

data class EngineChoice(val engine: Engine, val backend: Backend)

class EngineRegistry {
    private val engines = mutableListOf<Engine>()

    fun register(engine: Engine) {
        require(engines.none { it.id == engine.id }) { "Engine ${engine.id} already registered" }
        engines += engine
    }

    fun all(): List<Engine> = engines.toList()

    /**
     * Picks an engine and backend for [model]. With [preferred] null the order is
     * NPU, GPU, CPU; a preferred backend is tried first and falls back the same way.
     * [available] is what the device actually has.
     */
    fun resolve(
        model: ModelDescriptor,
        available: Set<Backend>,
        preferred: Backend? = null,
    ): EngineChoice? = candidates(model, available, preferred).firstOrNull()

    /**
     * Every engine and backend that can run [model], best first: [resolve] is the first one.
     * A backend can still fail at load time (a GPU without enough memory, a driver that
     * rejects a kernel), so callers that want a fallback load the first choice and move on
     * to the next when [Engine.load] throws.
     */
    fun candidates(
        model: ModelDescriptor,
        available: Set<Backend>,
        preferred: Backend? = null,
    ): List<EngineChoice> {
        val capable = engines.filter { model.capability in it.capabilities && it.supports(model) }
        val order = listOfNotNull(preferred) + Backend.entries.filter { it != preferred }
        return order.filter { it in available }.flatMap { backend ->
            capable.filter { backend in it.backends }.map { EngineChoice(it, backend) }
        }
    }
}
