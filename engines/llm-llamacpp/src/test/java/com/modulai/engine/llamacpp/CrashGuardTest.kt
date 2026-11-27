package com.modulai.engine.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val marker: File get() = File(tmp.root, "marker")
    private fun guard(stamp: String = "build-1") = CrashGuard(marker, stamp)

    @Test fun notTrippedWithoutMarker() {
        assertFalse(guard().tripped)
    }

    @Test fun markerExistsOnlyWhileTheStepRuns() {
        val result = guard().run {
            assertTrue(marker.exists())
            42
        }
        assertEquals(42, result)
        assertFalse(guard().tripped)
    }

    @Test fun failingStepIsNotACrash() {
        runCatching { guard().run { error("load failed") } }
        assertFalse(guard().tripped)
    }

    @Test fun stepThatNeverReturnedTripsTheNextProcess() {
        // What a killed process leaves behind: the marker, written but never removed.
        marker.writeText("build-1")
        assertTrue(guard().tripped)
    }

    @Test fun appUpdateClearsTheVerdict() {
        marker.writeText("build-1")
        assertFalse(guard("build-2").tripped)
    }

    @Test fun resetAllowsTheBackendAgain() {
        marker.writeText("build-1")
        guard().reset()
        assertFalse(guard().tripped)
    }

    // The GPU and the NPU each have a marker: a crash in one must not switch off the other.
    @Test fun guardsWithDifferentMarkersAreIndependent() {
        val gpu = CrashGuard(File(tmp.root, "llama-gpu.pending"), "build-1")
        val npu = CrashGuard(File(tmp.root, "llama-npu.pending"), "build-1")
        File(tmp.root, "llama-npu.pending").writeText("build-1")
        assertTrue(npu.tripped)
        assertFalse(gpu.tripped)
        gpu.run { assertTrue(npu.tripped) }
        assertTrue(npu.tripped)
        npu.reset()
        assertFalse(npu.tripped)
    }

    @Test fun nestedStepsOfDifferentGuardsLeaveNoMarker() {
        val gpu = CrashGuard(File(tmp.root, "a"), "build-1")
        val npu = CrashGuard(File(tmp.root, "b"), "build-1")
        gpu.run { npu.run { assertTrue(gpu.tripped && npu.tripped) } }
        assertFalse(gpu.tripped || npu.tripped)
    }
}
