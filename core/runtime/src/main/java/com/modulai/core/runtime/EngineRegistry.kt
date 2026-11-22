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
    ): EngineChoice? {
        val candidates = engines.filter { model.capability in it.capabilities && it.supports(model) }
        val order = listOfNotNull(preferred) + Backend.entries.filter { it != preferred }
        for (backend in order) {
            if (backend !in available) continue
            candidates.firstOrNull { backend in it.backends }?.let { return EngineChoice(it, backend) }
        }
        return null
    }
}
