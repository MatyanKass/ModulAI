package com.modulai.engine.llamacpp

import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.LoadConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class PlacementTest {
    @Test fun cpuNeverOffloads() {
        assertEquals(Placement(LlamaNative.DEVICE_CPU, 0, Backend.CPU), placementFor(LoadConfig(Backend.CPU)))
        assertEquals(
            Placement(LlamaNative.DEVICE_CPU, 0, Backend.CPU),
            placementFor(LoadConfig(Backend.CPU, gpuLayers = 12)),
        )
    }

    @Test fun gpuOffloadsAllLayersByDefault() {
        assertEquals(
            Placement(LlamaNative.DEVICE_GPU, LoadConfig.ALL_LAYERS, Backend.GPU),
            placementFor(LoadConfig(Backend.GPU)),
        )
    }

    @Test fun npuUsesTheGpuLayerSemantics() {
        assertEquals(
            Placement(LlamaNative.DEVICE_NPU, LoadConfig.ALL_LAYERS, Backend.NPU),
            placementFor(LoadConfig(Backend.NPU)),
        )
        assertEquals(Placement(LlamaNative.DEVICE_NPU, 8, Backend.NPU), placementFor(LoadConfig(Backend.NPU, gpuLayers = 8)))
    }

    // Zero layers is a CPU load and says so, for either accelerator.
    @Test fun zeroLayersIsReportedAsCpu() {
        assertEquals(Placement(LlamaNative.DEVICE_CPU, 0, Backend.CPU), placementFor(LoadConfig(Backend.GPU, gpuLayers = 0)))
        assertEquals(Placement(LlamaNative.DEVICE_CPU, 0, Backend.CPU), placementFor(LoadConfig(Backend.NPU, gpuLayers = 0)))
    }

    @Test fun npuNoteNamesTheFormatToUse() {
        assertTrue(NPU_QUANT_NOTE.contains("Q4_0"))
    }
}

class HtpSkelsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = ByteArray(4096) { it.toByte() }
    private var opened = 0
    private fun skels(stamp: String = "100", assets: Map<String, ByteArray> = mapOf("hexagon/libggml-htp-v79.so" to bytes)) =
        HtpSkels(File(tmp.root, "hexagon"), stamp) { name ->
            assets[name]?.let { opened++; ByteArrayInputStream(it) }
        }

    @Test fun copiesTheSkelForTheArchitecture() {
        val s = skels()
        assertTrue(s.install(79))
        assertArrayEquals(bytes, File(s.dir, "libggml-htp-v79.so").readBytes())
        assertFalse(File(s.dir, "libggml-htp-v79.so.part").exists())
    }

    @Test fun missingArchitectureIsReportedNotThrown() {
        val s = skels()
        assertFalse(s.install(73))
        assertFalse(File(s.dir, "libggml-htp-v73.so").exists())
    }

    @Test fun secondInstallReusesTheCopy() {
        skels().install(79)
        skels().install(79)
        assertEquals(1, opened)
    }

    @Test fun appUpdateReplacesTheOldCopies() {
        val old = skels("100")
        old.install(79)
        val new = skels("200")
        assertTrue(new.install(79))
        assertFalse(old.dir.exists())
        assertTrue(File(new.dir, "libggml-htp-v79.so").isFile)
        assertEquals(2, opened)
    }

    @Test fun interruptedCopyIsRedone() {
        val s = skels()
        s.dir.mkdirs()
        File(s.dir, "libggml-htp-v79.so.part").writeBytes(bytes.copyOf(10))
        assertTrue(s.install(79))
        assertArrayEquals(bytes, File(s.dir, "libggml-htp-v79.so").readBytes())
    }
}
