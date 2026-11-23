package com.modulai.core.agent

import com.modulai.core.runtime.Effort
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTunerTest {
    @Test fun keepsPreferenceWhenFastAndRoomy() {
        assertEquals(Effort.HIGH, AutoTuner.effort(Effort.HIGH, 20f, 0.2f))
        assertEquals(Effort.ULTRA, AutoTuner.effort(Effort.ULTRA, 0f, 0f))
    }

    @Test fun capsWhenSlowOrFull() {
        assertEquals(Effort.MEDIUM, AutoTuner.effort(Effort.HIGH, 5f, 0.2f))
        assertEquals(Effort.MEDIUM, AutoTuner.effort(Effort.ULTRA, 20f, 0.6f))
        assertEquals(Effort.LOW, AutoTuner.effort(Effort.HIGH, 2f, 0.2f))
        assertEquals(Effort.LOW, AutoTuner.effort(Effort.HIGH, 20f, 0.8f))
    }

    @Test fun neverRaisesEffort() {
        assertEquals(Effort.LOW, AutoTuner.effort(Effort.LOW, 50f, 0f))
    }
}
