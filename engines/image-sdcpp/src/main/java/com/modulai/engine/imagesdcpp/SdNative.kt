package com.modulai.engine.imagesdcpp

internal interface ProgressCallback {
    fun onProgress(stage: Int, done: Int, total: Int)
}

/**
 * Raw JNI surface of modulai_sd.cpp. Calls for one handle must not overlap, except
 * [cancel] and [cancelUpscaler]. Images are RGB bytes; `out` receives
 * [result code, width, height].
 */
internal object SdNative {
    const val RESULT_OK = 0
    const val RESULT_CANCELLED = 1

    const val STAGE_LOADING = 0
    const val STAGE_SAMPLING = 1
    const val STAGE_TILES = 2

    init {
        System.loadLibrary("modulai-sd")
    }

    external fun init()
    external fun cpuSupported(): Boolean
    external fun systemInfo(): String
    external fun lastError(): ByteArray
    external fun load(modelPath: String, vaePath: String, taesdPath: String, nThreads: Int): Long
    external fun free(handle: Long)
    external fun modelVersion(handle: Long): String
    external fun cancel(handle: Long)
    external fun generate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int,
        cfgScale: Float, sampler: String, scheduler: String, seed: Long,
        initRgb: ByteArray?, initWidth: Int, initHeight: Int, strength: Float,
        loraPaths: Array<String>, loraWeights: FloatArray, vaeTiling: Boolean,
        callback: ProgressCallback, out: IntArray,
    ): ByteArray?
    external fun loadUpscaler(modelPath: String, nThreads: Int): Long
    external fun freeUpscaler(handle: Long)
    external fun upscalerScale(handle: Long): Int
    external fun cancelUpscaler(handle: Long)
    external fun upscale(
        handle: Long, rgb: ByteArray, width: Int, height: Int, callback: ProgressCallback, out: IntArray,
    ): ByteArray?
}
