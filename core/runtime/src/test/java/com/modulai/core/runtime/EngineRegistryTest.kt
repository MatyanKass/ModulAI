package com.modulai.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRegistryTest {
    private class FakeEngine(
        override val id: String,
        override val backends: Set<Backend>,
        private val format: ModelFormat,
    ) : Engine {
        override val capabilities = setOf(Capability.TEXT_GENERATION)
        override fun supports(model: ModelDescriptor) = model.format == format
        override suspend fun load(model: ModelDescriptor, config: LoadConfig): Session = error("unused")
    }

    private val gguf = ModelDescriptor("m", "/m.gguf", ModelFormat.GGUF, Capability.TEXT_GENERATION, 1)
    private val qnn = gguf.copy(format = ModelFormat.QNN)
    private val registry = EngineRegistry().apply {
        register(FakeEngine("llama", setOf(Backend.GPU, Backend.CPU), ModelFormat.GGUF))
        register(FakeEngine("qnn", setOf(Backend.NPU), ModelFormat.QNN))
    }
    private val all = Backend.entries.toSet()

    @Test fun ggufNeverLandsOnNpu() {
        val c = registry.resolve(gguf, all)!!
        assertEquals("llama", c.engine.id)
        assertEquals(Backend.GPU, c.backend)
    }

    @Test fun qnnModelUsesNpu() {
        assertEquals(Backend.NPU, registry.resolve(qnn, all)!!.backend)
    }

    @Test fun preferredBackendWins() {
        assertEquals(Backend.CPU, registry.resolve(gguf, all, preferred = Backend.CPU)!!.backend)
    }

    @Test fun fallsBackWhenPreferredMissing() {
        assertEquals(Backend.CPU, registry.resolve(gguf, setOf(Backend.CPU), preferred = Backend.GPU)!!.backend)
    }

    @Test fun nullWhenNothingFits() {
        assertNull(registry.resolve(qnn, setOf(Backend.CPU)))
    }

    @Test fun candidatesListTheGpuToCpuFallback() {
        val c = registry.candidates(gguf, all)
        assertEquals(listOf("llama" to Backend.GPU, "llama" to Backend.CPU), c.map { it.engine.id to it.backend })
    }

    @Test fun candidatesStartWithThePreferredBackend() {
        assertEquals(
            listOf(Backend.CPU, Backend.GPU),
            registry.candidates(gguf, all, preferred = Backend.CPU).map { it.backend },
        )
    }

    // An engine that found no usable GPU does not list it, whatever the device profile says.
    @Test fun candidatesSkipBackendsTheEngineLacks() {
        val cpuOnly = EngineRegistry().apply { register(FakeEngine("llama", setOf(Backend.CPU), ModelFormat.GGUF)) }
        assertEquals(listOf(Backend.CPU), cpuOnly.candidates(gguf, all, preferred = Backend.GPU).map { it.backend })
    }

    @Test fun resolveIsTheFirstCandidate() {
        assertEquals(registry.candidates(gguf, all).first(), registry.resolve(gguf, all))
        assertTrue(registry.candidates(qnn, setOf(Backend.CPU)).isEmpty())
    }
}
