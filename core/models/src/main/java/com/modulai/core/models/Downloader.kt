package com.modulai.core.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(val doneBytes: Long, val totalBytes: Long) {
    val fraction get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}

object Downloader {
    /**
     * Downloads [url] to [target]. Data goes to `<target>.part` first, so an interrupted
     * download resumes from where it stopped the next time the same call is made.
     * The flow completes once [target] exists.
     */
    fun download(url: String, target: File, token: String? = null): Flow<DownloadProgress> = flow {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        var done = if (part.exists()) part.length() else 0L

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")

            val code = conn.responseCode
            if (code == 416) {
                // The .part file already holds everything.
            } else {
                if (code == 200) done = 0 // server ignored the range, start over
                else if (code != 206) throw IOException("Download failed: HTTP $code")
                val total = done + conn.contentLengthLong.coerceAtLeast(0)
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(done)
                    out.seek(done)
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(1 shl 16)
                        emit(DownloadProgress(done, total))
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            emit(DownloadProgress(done, total))
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("Connection closed at $done of $total bytes")
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(target)) throw IOException("Could not move download to ${target.name}")
    }.conflate().flowOn(Dispatchers.IO)
}
