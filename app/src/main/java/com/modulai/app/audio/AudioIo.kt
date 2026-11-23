package com.modulai.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** Why an audio action could not be carried out; the UI turns [reason] into a localized message. */
class AudioException(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason {
        NO_MIC_PERMISSION, MIC_FAILED, NOTHING_RECORDED, NO_SPEECH_MODEL, NO_VOICE, NO_CLONING_MODEL,
        SAMPLE_TOO_SHORT, UNREADABLE_FILE,
    }
}

fun hasMicPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Records the microphone at 16 kHz mono, the rate speech recognition works at. */
class MicRecorder(private val context: Context) {
    @Volatile
    private var stopRequested = false

    /** Ends the running [record] call normally, so that it returns what was captured. */
    fun stop() {
        stopRequested = true
    }

    /**
     * Records until [stop], [maxSeconds], or, with [stopOnSilence], until the speaker has been
     * quiet for a moment after saying something. [onLevel] gets the loudness of each 100 ms, 0..1.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(maxSeconds: Int, stopOnSilence: Boolean, onLevel: (Float) -> Unit): Pcm = withContext(Dispatchers.IO) {
        if (!hasMicPermission(context)) throw AudioException(AudioException.Reason.NO_MIC_PERMISSION)
        stopRequested = false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw AudioException(AudioException.Reason.MIC_FAILED)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE),
        )
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw AudioException(AudioException.Reason.MIC_FAILED)
            record.startRecording()
            val block = ShortArray(SAMPLE_RATE / 10)
            val all = ShortArray(maxSeconds * SAMPLE_RATE)
            var size = 0
            var heardSpeech = false
            var quietBlocks = 0
            while (isActive && !stopRequested && size < all.size) {
                val n = record.read(block, 0, minOf(block.size, all.size - size))
                if (n < 0) throw AudioException(AudioException.Reason.MIC_FAILED)
                if (n == 0) continue
                block.copyInto(all, size, 0, n)
                size += n
                var energy = 0.0
                for (i in 0 until n) energy += (block[i] / 32768.0).let { it * it }
                val level = sqrt(energy / n).toFloat()
                onLevel((level * 6).coerceAtMost(1f))
                if (level > SPEECH_LEVEL) {
                    heardSpeech = true
                    quietBlocks = 0
                } else if (heardSpeech && ++quietBlocks >= QUIET_BLOCKS_TO_STOP && stopOnSilence) {
                    break
                }
            }
            coroutineContext.ensureActive()
            if (size < SAMPLE_RATE / 4) throw AudioException(AudioException.Reason.NOTHING_RECORDED)
            Pcm.fromShorts(all.copyOf(size), SAMPLE_RATE)
        } finally {
            onLevel(0f)
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SPEECH_LEVEL = 0.02f
        const val QUIET_BLOCKS_TO_STOP = 15
    }
}

/** Plays PCM through the media stream. */
class PcmPlayer {
    /** Returns when the sound has been played to the end; cancelling stops it at once. */
    suspend fun play(pcm: Pcm) = withContext(Dispatchers.IO) {
        if (pcm.samples.isEmpty()) return@withContext
        val format = AudioFormat.Builder()
            .setSampleRate(pcm.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bufferBytes = AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            .coerceAtLeast(pcm.sampleRate)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var at = 0
            val chunk = pcm.sampleRate / 10
            while (isActive && at < pcm.samples.size) {
                val n = track.write(pcm.samples, at, minOf(chunk, pcm.samples.size - at), AudioTrack.WRITE_BLOCKING)
                if (n < 0) throw IOException("Audio output failed ($n)")
                at += n
            }
            // stop() lets the buffered tail play out; wait for the hardware to get there.
            track.stop()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (isActive && track.playbackHeadPosition < pcm.samples.size && System.nanoTime() < deadline) delay(20)
        } finally {
            // Cut off whatever is still buffered when playback is abandoned half-way.
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }
}

class DecodedAudio(val pcm: Pcm, /** The file was longer than the limit and only its beginning was read. */ val truncated: Boolean)

/** Reads the first audio track of any media file Android can decode and mixes it down to mono. */
object AudioFileDecoder {
    suspend fun decode(context: Context, uri: Uri, maxSeconds: Int): DecodedAudio = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioException(AudioException.Reason.UNREADABLE_FILE, e.message)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw AudioException(AudioException.Reason.UNREADABLE_FILE)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val decoder = try {
                MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()).also {
                    it.configure(inputFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw AudioException(AudioException.Reason.UNREADABLE_FILE, e.message)
            }
            codec = decoder

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val chunks = ArrayList<FloatArray>()
            var frames = 0L
            var truncated = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (index >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val mono = if (float) mixDown(buffer.asFloatBuffer(), channels) else mixDown(buffer.asShortBuffer(), channels)
                        chunks += mono
                        frames += mono.size
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (frames >= maxSeconds.toLong() * sampleRate) {
                        truncated = !outputDone
                        outputDone = true
                    }
                }
            }
            if (frames == 0L) throw AudioException(AudioException.Reason.UNREADABLE_FILE)
            val samples = FloatArray(frames.toInt())
            var at = 0
            for (c in chunks) {
                c.copyInto(samples, at)
                at += c.size
            }
            DecodedAudio(Pcm(samples, sampleRate), truncated)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    private fun mixDown(buffer: java.nio.ShortBuffer, channels: Int) = FloatArray(buffer.remaining() / channels) {
        var sum = 0
        for (c in 0 until channels) sum += buffer.get()
        sum / (channels * 32768f)
    }

    private fun mixDown(buffer: java.nio.FloatBuffer, channels: Int) = FloatArray(buffer.remaining() / channels) {
        var sum = 0f
        for (c in 0 until channels) sum += buffer.get()
        sum / channels
    }
}

/** Saves [pcm] as a WAV file into the shared Music/ModulAI folder and returns its display name. */
suspend fun saveWavToMusic(context: Context, pcm: Pcm, baseName: String): String = withContext(Dispatchers.IO) {
    val name = "$baseName.wav"
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/ModulAI")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: throw IOException("The media library refused the file")
    try {
        resolver.openOutputStream(uri)?.use { it.write(Wav.encode(pcm)) } ?: throw IOException("Could not write $name")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    name
}
