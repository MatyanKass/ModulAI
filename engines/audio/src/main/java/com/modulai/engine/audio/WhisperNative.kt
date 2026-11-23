package com.modulai.engine.audio

interface WhisperCallback {
    /** Percent of the recording processed so far. */
    fun onProgress(percent: Int)
    fun onSegment(startMs: Long, endMs: Long, utf8: ByteArray)
}

/** Raw JNI surface of modulai_audio.cpp. Calls for one handle must not overlap, except [cancel]. */
object WhisperNative {
    const val DONE = 0
    const val CANCELLED = 1
    const val ERR_FAILED = -1
    const val ERR_LANGUAGE = -2

    init {
        System.loadLibrary("modulai-audio")
    }

    external fun systemInfo(): String
    external fun lastError(): String

    /** Returns 0 when the file cannot be loaded; [lastError] then says why. */
    external fun load(path: String): Long
    external fun free(handle: Long)
    external fun isMultilingual(handle: Long): Boolean

    /** Makes [transcribe] return [CANCELLED], now and on later calls, until [clearCancel]. */
    external fun cancel(handle: Long)
    external fun clearCancel(handle: Long)

    /** Language code whisper settled on in the last [transcribe] call. */
    external fun detectedLanguage(handle: Long): String

    /** [samples] are 16 kHz mono; [language] is a whisper code such as "ru", or "auto". */
    external fun transcribe(
        handle: Long, samples: FloatArray, language: String, translate: Boolean, threads: Int,
        callback: WhisperCallback,
    ): Int
}
