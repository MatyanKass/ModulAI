package com.modulai.engine.llamacpp

import com.modulai.core.runtime.Backend
import com.modulai.core.runtime.LoadConfig
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Where a load runs: the native device kind, the layers it gets and the backend reported for it. */
internal data class Placement(val device: Int, val layers: Int, val backend: Backend)

/**
 * [Backend.GPU] and [Backend.NPU] offload [LoadConfig.gpuLayers] layers to that one device; 0
 * layers is a CPU load, reported as such so a caller never mistakes it for an accelerated one.
 */
internal fun placementFor(config: LoadConfig): Placement {
    val device = when (config.backend) {
        Backend.GPU -> LlamaNative.DEVICE_GPU
        Backend.NPU -> LlamaNative.DEVICE_NPU
        Backend.CPU -> LlamaNative.DEVICE_CPU
    }
    if (device == LlamaNative.DEVICE_CPU || config.gpuLayers == 0) {
        return Placement(LlamaNative.DEVICE_CPU, 0, Backend.CPU)
    }
    return Placement(device, config.gpuLayers, config.backend)
}

/**
 * The DSP half of the Hexagon backend, libggml-htp-v<arch>.so, is Hexagon code, not ARM: the
 * APK carries one per DSP generation under assets/hexagon/ (see build.gradle.kts for why not
 * in lib/), and the one this phone's NPU runs is copied to [dir], where the DSP loader reads
 * it over FastRPC when the first NPU session opens. [dir] is named after the installed build,
 * so an update brings fresh copies and the old directory is removed.
 */
internal class HtpSkels(
    private val root: File,
    stamp: String,
    private val open: (String) -> InputStream?,
) {
    val dir = File(root, stamp)

    fun assetName(arch: Int) = "hexagon/libggml-htp-v$arch.so"

    /** Puts the skel for Hexagon v[arch] into [dir]; false when this build carries none for it. */
    @Throws(IOException::class)
    fun install(arch: Int): Boolean {
        root.listFiles()?.filter { it.name != dir.name }?.forEach { it.deleteRecursively() }
        val target = File(dir, File(assetName(arch)).name)
        if (target.isFile && target.length() > 0) return true
        val input = open(assetName(arch)) ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val partial = File(dir, target.name + ".part")
        input.use { src -> partial.outputStream().use { src.copyTo(it) } }
        if (!partial.renameTo(target)) throw IOException("cannot rename $partial")
        return true
    }
}

/** Shown next to the NPU choice: what the Hexagon backend accelerates (ggml-hexagon, htp-kparams.cpp). */
const val NPU_QUANT_NOTE =
    "The NPU accelerates models quantised as Q4_0, Q8_0, MXFP4, IQ4_NL, Q4_1/Q5_0/Q5_1, " +
        "Q2_K-Q6_K or F16. Q4_0 is what llama.cpp tests on Snapdragon; other formats " +
        "(IQ1, IQ2_S, IQ3, ...) fall back to the CPU and can make the NPU slower than the CPU."
