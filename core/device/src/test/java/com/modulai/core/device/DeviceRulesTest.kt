package com.modulai.core.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRulesTest {
    @Test fun parsesMeminfo() {
        val m = MemInfo.parse(
            """
            MemTotal:       11534336 kB
            MemFree:          204800 kB
            MemAvailable:    4194304 kB
            SwapTotal:       6291456 kB
            SwapFree:        3145728 kB
            """.trimIndent(),
        )
        assertEquals(11534336L * 1024, m.totalBytes)
        assertEquals(4194304L * 1024, m.availableBytes)
        assertEquals(6291456L * 1024, m.swapTotalBytes)
        assertEquals(3145728L * 1024, m.swapFreeBytes)
    }

    @Test fun missingSwapIsZero() {
        assertEquals(0, MemInfo.parse("MemTotal: 100 kB").swapTotalBytes)
    }

    @Test fun snapdragon8EliteHasNpu() {
        assertEquals(NpuKind.QUALCOMM_HEXAGON, SocRules.npuFor("QTI", "SM8750"))
        assertEquals(NpuKind.QUALCOMM_HEXAGON, SocRules.npuFor("Qualcomm", "sm8550-ab"))
    }

    @Test fun oldOrForeignSocHasNoNpu() {
        assertEquals(NpuKind.NONE, SocRules.npuFor("QTI", "SM8350"))
        assertEquals(NpuKind.NONE, SocRules.npuFor("Mediatek", "MT6989"))
        assertEquals(NpuKind.NONE, SocRules.npuFor("QTI", "unknown"))
    }

    @Test fun countsBigCores() {
        assertEquals(2, SocRules.countBigCores(listOf(3532800, 3532800, 3532800, 3532800, 3532800, 3532800, 4320000, 4320000)))
        assertEquals(0, SocRules.countBigCores(emptyList()))
    }
}
