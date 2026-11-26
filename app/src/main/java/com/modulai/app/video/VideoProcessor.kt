package com.modulai.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.modulai.app.image.ImageIo
import com.modulai.core.runtime.ImageData
import com.modulai.core.runtime.ImageEvent
import com.modulai.core.runtime.UpscaleSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

enum class VideoSize {
    /** Same frame size, every frame passed through the upscaler and scaled back: sharper, no zoom. */
    SAME,
    /** Twice the size, capped so the hardware encoder accepts it. */
    DOUBLE,
}

data class VideoJob(
    val source: Uri,
    val target: File,
    /** Run every frame through [upscaler]; null leaves frames as they are. */
    val upscaler: UpscaleSession?,
    val size: VideoSize,
    /** Insert one blended frame between every pair, doubling the frame rate. */
    val doubleFps: Boolean,
)

data class VideoProgress(val frame: Int, val frames: Int)

data class VideoInfo(val width: Int, val height: Int, val frames: Int, val durationMs: Long, val fps: Float)

/**
 * Re-encodes a video frame by frame. Frames are read with MediaMetadataRetriever (slow but
 * simple and exact), encoded to H.264 through byte-buffer input so every frame gets an exact
 * timestamp, and the original audio track is copied unchanged.
 */
class VideoProcessor(private val context: Context) {

    fun inspect(source: Uri): VideoInfo = MediaMetadataRetriever().use { r ->
        r.setDataSource(context, source)
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        // Retrieved frames come out already rotated.
        if (rotation == 90 || rotation == 270) w = h.also { h = w }
        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        if (w <= 0 || h <= 0 || frames <= 0) throw IOException("Not a video, or its frames cannot be read")
        VideoInfo(w, h, frames, duration, if (duration > 0) frames * 1000f / duration else 30f)
    }

    suspend fun run(job: VideoJob, onProgress: (VideoProgress) -> Unit) {
        val info = inspect(job.source)
        val (outW, outH) = outputSize(info.width, info.height, job.size)
        val outFps = if (job.doubleFps) info.fps * 2 else info.fps
        val frameUs = (1_000_000.0 / outFps)
        val total = if (job.doubleFps) info.frames * 2 - 1 else info.frames

        job.target.parentFile?.mkdirs()
        val muxer = MediaMuxer(job.target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encoder = createEncoder(outW, outH, outFps, bitrate(outW, outH, outFps))
        var videoTrack = -1
        var audio: AudioCopy? = null
        var muxing = false
        val info2 = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info2, if (endOfStream) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audio = AudioCopy.prepare(context, job.source, muxer)
                        muxer.start()
                        muxing = true
                    }
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)!!
                        if (info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info2.size = 0
                        if (info2.size > 0 && muxing) muxer.writeSampleData(videoTrack, buffer, info2)
                        encoder.releaseOutputBuffer(index, false)
                        if (info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun submit(frame: Bitmap?, index: Int) {
            while (true) {
                val input = encoder.dequeueInputBuffer(10_000)
                if (input < 0) {
                    drain(false)
                    continue
                }
                val pts = (index * frameUs).toLong()
                if (frame == null) {
                    encoder.queueInputBuffer(input, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    val image = encoder.getInputImage(input)!!
                    Yuv.write(frame, image)
                    encoder.queueInputBuffer(input, 0, outW * outH * 3 / 2, pts, 0)
                }
                drain(false)
                return
            }
        }

        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, job.source)
                var previous: Bitmap? = null
                var written = 0
                for (i in 0 until info.frames) {
                    currentCoroutineContext().ensureActive()
                    val raw = retriever.getFrameAtIndex(i) ?: continue
                    val frame = process(raw, job, outW, outH)
                    if (job.doubleFps && previous != null) {
                        val mid = Frames.blend(previous, frame)
                        submit(mid, written++)
                        mid.recycle()
                    }
                    submit(frame, written++)
                    previous?.recycle()
                    previous = frame
                    onProgress(VideoProgress(written, total))
                }
                previous?.recycle()
                submit(null, written)
            }
            drain(true)
            audio?.copy()
        } catch (e: Throwable) {
            job.target.delete()
            throw e
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
            audio?.close()
        }
    }

    private suspend fun process(raw: Bitmap, job: VideoJob, outW: Int, outH: Int): Bitmap {
        val upscaler = job.upscaler
        val source = if (raw.config == Bitmap.Config.ARGB_8888) raw else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        if (upscaler == null) return Frames.scale(source, outW, outH)
        val data = ImageIo.toImageData(source)
        source.recycle()
        var result: ImageData? = null
        upscaler.upscale(data).collect { if (it is ImageEvent.Done) result = it.image }
        val big = ImageIo.toBitmap(result ?: throw IOException("The upscaler returned nothing"))
        return Frames.scale(big, outW, outH)
    }

    private fun createEncoder(w: Int, h: Int, fps: Float, bitrate: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    }

    companion object {
        /** Hardware H.264 encoders reliably accept up to 1920x1080; dimensions must be even. */
        private const val MAX_LONG = 1920
        private const val MAX_SHORT = 1088

        fun outputSize(w: Int, h: Int, size: VideoSize): Pair<Int, Int> {
            val factor = if (size == VideoSize.DOUBLE) 2.0 else 1.0
            val long = maxOf(w, h) * factor
            val short = minOf(w, h) * factor
            val fit = minOf(1.0, MAX_LONG / long, MAX_SHORT / short)
            fun even(v: Double) = maxOf(2, (v / 2).toInt() * 2)
            return even(w * factor * fit) to even(h * factor * fit)
        }

        fun bitrate(w: Int, h: Int, fps: Float) = (w * h * fps * 0.15f).toInt().coerceIn(1_000_000, 40_000_000)
    }
}

/** Copies the source's first audio track into the output unchanged. */
private class AudioCopy(private val extractor: MediaExtractor, private val muxer: MediaMuxer, private val track: Int) {
    fun copy() {
        val buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    fun close() = extractor.release()

    companion object {
        fun prepare(context: Context, source: Uri, muxer: MediaMuxer): AudioCopy? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, source, null)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null.also { extractor.release() }
                extractor.selectTrack(index)
                AudioCopy(extractor, muxer, muxer.addTrack(extractor.getTrackFormat(index)))
            } catch (e: Exception) {
                // A video without usable audio is still worth producing.
                extractor.release()
                null
            }
        }
    }
}
