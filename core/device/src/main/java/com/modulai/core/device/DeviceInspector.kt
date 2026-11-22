package com.modulai.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

class DeviceInspector(private val context: Context) {

    fun inspect(): DeviceProfile {
        val socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.BOARD
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).mapNotNull { readLong("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socManufacturer = socManufacturer,
            socModel = socModel,
            cpuCores = cores,
            bigCores = SocRules.countBigCores(freqs),
            memory = readMemory(),
            npu = SocRules.npuFor(socManufacturer, socModel),
        )
    }

    private fun readMemory(): MemInfo {
        val proc = runCatching { MemInfo.parse(File("/proc/meminfo").readText()) }.getOrNull()
        if (proc != null && proc.totalBytes > 0) return proc
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return MemInfo(info.totalMem, info.availMem, 0, 0)
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()
}
