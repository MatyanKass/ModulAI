package com.modulai.engine.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GpuCrashGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val marker: File get() = File(tmp.root, "marker")
    private fun guard(stamp: String = "build-1") = GpuCrashGuard(marker, stamp)

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

    @Test fun resetAllowsTheGpuAgain() {
        marker.writeText("build-1")
        guard().reset()
        assertFalse(guard().tripped)
    }
}
