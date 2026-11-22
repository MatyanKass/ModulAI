package com.modulai.core.device

import com.modulai.core.runtime.Backend

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String,
    val socModel: String,
    val cpuCores: Int,
    /** Cores whose max frequency is the highest on the device. */
    val bigCores: Int,
    val memory: MemInfo,
    val npu: NpuKind,
) {
    val backends: Set<Backend>
        get() = buildSet {
            add(Backend.CPU)
            add(Backend.GPU)
            if (npu != NpuKind.NONE) add(Backend.NPU)
        }
}

enum class NpuKind { NONE, QUALCOMM_HEXAGON }

object SocRules {
    /**
     * Hexagon NPUs usable through QNN start with Snapdragon 8 Gen 1 (SM8450).
     * Model codes look like SM8750; anything below 8450 is treated as unsupported.
     */
    fun npuFor(socManufacturer: String, socModel: String): NpuKind {
        val vendor = socManufacturer.lowercase()
        if (vendor != "qualcomm" && vendor != "qti") return NpuKind.NONE
        val code = Regex("""SM(\d{4})""").find(socModel.uppercase())?.groupValues?.get(1)?.toIntOrNull()
            ?: return NpuKind.NONE
        return if (code >= 8450) NpuKind.QUALCOMM_HEXAGON else NpuKind.NONE
    }

    fun countBigCores(maxFreqs: List<Long>): Int {
        val top = maxFreqs.maxOrNull() ?: return 0
        return maxFreqs.count { it == top }
    }
}
