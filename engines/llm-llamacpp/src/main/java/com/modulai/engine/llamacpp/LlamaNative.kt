package com.modulai.engine.llamacpp

internal interface PieceCallback {
    fun onPiece(utf8: ByteArray)
    fun onToolCall(name: ByteArray, argumentsJson: ByteArray)
}

/** Raw JNI surface of modulai_llama.cpp. Calls for one handle must not overlap, except [cancel]. */
internal object LlamaNative {
    const val STOP_EOS = 0
    const val STOP_LENGTH = 1
    const val STOP_CANCELLED = 2
    const val ERR_TEMPLATE = -1
    const val ERR_PROMPT_TOO_LONG = -2
    const val ERR_DECODE = -3

    init {
        System.loadLibrary("modulai-llama")
    }

    /** Loads the CPU backends only. */
    external fun init(nativeLibDir: String)

    /**
     * Loads the OpenCL backend and returns a description of every GPU it can use; empty when
     * there is none, with the reason in [lastError]. May kill the process on a broken driver.
     */
    external fun loadGpuBackend(nativeLibDir: String, kernelCacheDir: String): Array<String>
    external fun systemInfo(): String
    external fun backendCount(): Int
    external fun lastError(): String

    /** [nGpuLayers] 0 keeps the GPU untouched, a negative value offloads every layer. Returns 0 on failure. */
    external fun load(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int, useMlock: Boolean, useMmap: Boolean,
    ): Long
    external fun free(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun cancel(handle: Long)
    external fun countTokens(
        handle: Long, roles: Array<String>, contents: Array<String>, tools: Array<String>, thinking: Boolean,
    ): Int
    external fun generate(
        handle: Long, roles: Array<String>, contents: Array<String>, tools: Array<String>,
        temperature: Float, topP: Float, topK: Int, minP: Float, repeatPenalty: Float,
        maxTokens: Int, seed: Int, thinking: Boolean, thinkingBudget: Int, callback: PieceCallback,
    ): Int
}
