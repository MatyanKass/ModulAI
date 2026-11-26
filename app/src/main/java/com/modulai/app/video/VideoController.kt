package com.modulai.app.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.modulai.app.R
import com.modulai.app.image.ImageController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class VideoState(
    val source: Uri? = null,
    val info: VideoInfo? = null,
    val upscale: Boolean = true,
    val size: VideoSize = VideoSize.SAME,
    val doubleFps: Boolean = false,
    val running: Boolean = false,
    val progress: VideoProgress? = null,
    val startedAt: Long = 0,
    /** The finished file in the gallery. */
    val result: Uri? = null,
    val error: String? = null,
)

/** One video job at a time, process-wide so it survives leaving the screen. */
class VideoController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processor = VideoProcessor(context)
    private val _state = MutableStateFlow(VideoState())
    val state: StateFlow<VideoState> = _state
    private var job: Job? = null

    fun pick(uri: Uri) {
        if (_state.value.running) return
        _state.update { it.copy(source = uri, info = null, result = null, error = null) }
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { processor.inspect(uri) }
                _state.update { it.copy(info = info) }
            } catch (e: Exception) {
                _state.update { it.copy(source = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun options(upscale: Boolean = _state.value.upscale, size: VideoSize = _state.value.size, doubleFps: Boolean = _state.value.doubleFps) =
        _state.update { it.copy(upscale = upscale, size = size, doubleFps = doubleFps) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun start() {
        val s = _state.value
        val source = s.source ?: return
        if (s.running) return
        _state.update { it.copy(running = true, progress = null, result = null, error = null, startedAt = SystemClock.elapsedRealtime()) }
        job = scope.launch {
            val out = File(context.cacheDir, "video/out-${System.currentTimeMillis()}.mp4")
            try {
                val upscaler = if (s.upscale) {
                    ImageController.get(context).frameUpscaler()
                        ?: throw IOException(context.getString(R.string.video_err_no_upscaler))
                } else {
                    null
                }
                withContext(Dispatchers.Default) {
                    processor.run(VideoJob(source, out, upscaler, s.size, s.doubleFps)) { p ->
                        _state.update { it.copy(progress = p) }
                    }
                }
                val uri = withContext(Dispatchers.IO) { publish(out) }
                _state.update { it.copy(result = uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                out.delete()
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun publish(file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ModulAI-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ModulAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore refused the video")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("MediaStore gave no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    companion object {
        @Volatile private var instance: VideoController? = null

        fun get(context: Context): VideoController =
            instance ?: synchronized(this) { instance ?: VideoController(context.applicationContext).also { instance = it } }
    }
}
