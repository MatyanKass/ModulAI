package com.modulai.core.device

/** Values from /proc/meminfo, in bytes. */
data class MemInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
) {
    companion object {
        fun parse(text: String): MemInfo {
            val kb = HashMap<String, Long>()
            for (line in text.lineSequence()) {
                val key = line.substringBefore(':', "").trim()
                if (key.isEmpty()) continue
                val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: continue
                kb[key] = value
            }
            fun bytes(key: String) = (kb[key] ?: 0L) * 1024
            return MemInfo(bytes("MemTotal"), bytes("MemAvailable"), bytes("SwapTotal"), bytes("SwapFree"))
        }
    }
}
