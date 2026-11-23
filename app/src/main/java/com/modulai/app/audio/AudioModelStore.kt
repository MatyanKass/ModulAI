package com.modulai.app.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.modulai.app.DownloadItem
import com.modulai.app.DownloadQueue
import com.modulai.app.DownloadState
import com.modulai.app.R
import com.modulai.core.models.Downloader
import com.modulai.core.models.HfLink
import com.modulai.core.models.ModelStore
import com.modulai.core.runtime.Capability
import com.modulai.core.runtime.ModelDescriptor
import com.modulai.core.runtime.ModelFormat
import com.modulai.engine.audio.OnnxTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

enum class AudioModelKind { SPEECH_TO_TEXT, TEXT_TO_SPEECH }

data class AudioModelFile(val path: String, val sizeBytes: Long) {
    val name get() = path.substringAfterLast('/')
}

/** A model the app knows where to get. Sizes are the exact byte counts on Hugging Face. */
data class AudioModelSpec(
    val id: String,
    val kind: AudioModelKind,
    val title: String,
    @StringRes val description: Int,
    val repo: String,
    val files: List<AudioModelFile>,
) {
    val sizeBytes = files.sumOf { it.sizeBytes }
}

data class AudioDownload(val doneBytes: Long, val totalBytes: Long, val failed: Boolean, val error: String?) {
    val fraction get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
}

/**
 * Audio models on disk, under `audio-models/` in the app's external files. A recognition model is
 * one whisper `.bin` file; a speech model is a directory of ONNX graphs and voice files.
 */
class AudioModelStore(private val context: Context, private val ttsEngine: OnnxTtsEngine) {
    val dir: File = (context.getExternalFilesDir("audio-models") ?: File(context.filesDir, "audio-models")).apply { mkdirs() }

    fun speechToTextModels(): List<ModelDescriptor> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bin", ignoreCase = true) }
            .orEmpty().sortedBy { it.length() }
            .map { ModelDescriptor(titleOf(it.nameWithoutExtension), it.path, ModelFormat.GGUF, Capability.SPEECH_TO_TEXT, it.length()) }

    fun textToSpeechModels(): List<ModelDescriptor> =
        dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
            .filter { d -> CATALOG.find { it.id == d.name }?.let { isInstalled(it) } ?: true }
            .map { d ->
                ModelDescriptor(
                    titleOf(d.name), d.path, ModelFormat.ONNX, Capability.TEXT_TO_SPEECH,
                    d.listFiles().orEmpty().sumOf { it.length() },
                )
            }
            .filter { ttsEngine.inspect(it) != null }

    fun isInstalled(spec: AudioModelSpec) = spec.files.all { fileFor(spec, it).length() == it.sizeBytes }

    /** Queues every missing file; finished ones are kept, interrupted ones resume. */
    fun download(spec: AudioModelSpec) {
        for (file in spec.files) {
            val target = fileFor(spec, file)
            if (target.length() == file.sizeBytes) continue
            // A file of the wrong size is a leftover of some other version and cannot be resumed.
            target.delete()
            DownloadQueue.enqueue(context, HfLink.File(spec.repo, "main", file.path), target)
        }
    }

    fun cancelDownload(spec: AudioModelSpec, queue: List<DownloadItem>) {
        val targets = spec.files.mapTo(HashSet()) { fileFor(spec, it) }
        queue.filter { it.target in targets }.forEach { DownloadQueue.cancel(it) }
    }

    /** Progress over all files of [spec], or null when nothing of it is queued. */
    fun downloadOf(spec: AudioModelSpec, queue: List<DownloadItem>): AudioDownload? {
        val items = queue.filter { item -> spec.files.any { fileFor(spec, it) == item.target } }
        if (items.isEmpty()) return null
        var done = 0L
        for (file in spec.files) {
            val target = fileFor(spec, file)
            done += if (target.isFile) target.length() else Downloader.partFile(target).length()
        }
        val failed = items.firstOrNull { it.state == DownloadState.FAILED }
        return AudioDownload(done, spec.sizeBytes, failed != null, failed?.error)
    }

    fun delete(model: ModelDescriptor) {
        File(model.path).deleteRecursively()
    }

    fun delete(spec: AudioModelSpec) {
        File(pathOf(spec)).deleteRecursively()
    }

    /** Where [spec] lives once installed: the path its [ModelDescriptor] carries. */
    fun pathOf(spec: AudioModelSpec): String =
        if (spec.kind == AudioModelKind.SPEECH_TO_TEXT) fileFor(spec, spec.files.single()).path else File(dir, spec.id).path

    /**
     * Copies a model picked through the system picker: a whisper `.bin` file, or a `.zip` with the
     * files of a speech model, which is unpacked into a directory named after the archive.
     */
    suspend fun import(uri: Uri): AudioModelKind = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw IOException("The file has no name")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $name")
        input.use {
            when {
                name.endsWith(".bin", ignoreCase = true) -> {
                    val target = File(dir, ModelStore.sanitize(name))
                    val part = Downloader.partFile(target)
                    part.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                    if (!part.renameTo(target)) throw IOException("Could not store $name")
                    AudioModelKind.SPEECH_TO_TEXT
                }
                name.endsWith(".zip", ignoreCase = true) -> {
                    val target = File(dir, ModelStore.sanitize(name.dropLast(4)))
                    unzip(input, target)
                    val model = ModelDescriptor(target.name, target.path, ModelFormat.ONNX, Capability.TEXT_TO_SPEECH, 0)
                    if (ttsEngine.inspect(model) == null) {
                        target.deleteRecursively()
                        throw IOException("$name does not contain a speech model this app can run")
                    }
                    AudioModelKind.TEXT_TO_SPEECH
                }
                else -> throw IOException("Only whisper .bin files and .zip archives of speech models can be imported")
            }
        }
    }

    private fun unzip(input: java.io.InputStream, target: File) {
        target.mkdirs()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Models are flat; taking only the last path segment also keeps entries inside the folder.
                val name = ModelStore.sanitize(entry.name.substringAfterLast('/'))
                if (name.isEmpty()) continue
                File(target, name).outputStream().use { zip.copyTo(it, 1 shl 20) }
            }
        }
    }

    private fun fileFor(spec: AudioModelSpec, file: AudioModelFile) =
        if (spec.kind == AudioModelKind.SPEECH_TO_TEXT) File(dir, file.name) else File(File(dir, spec.id), file.name)

    private fun titleOf(name: String) =
        CATALOG.find { it.id == name || it.files.singleOrNull()?.name?.substringBeforeLast('.') == name }?.title ?: name

    companion object {
        private const val WHISPER = "ggerganov/whisper.cpp"
        private const val SUPERTONIC = "Supertone/supertonic-3"
        private const val CHATTERBOX = "onnx-community/chatterbox-multilingual-ONNX"

        private fun whisper(file: String, size: Long, title: String, @StringRes description: Int) =
            AudioModelSpec(file.removeSuffix(".bin"), AudioModelKind.SPEECH_TO_TEXT, title, description, WHISPER, listOf(AudioModelFile(file, size)))

        /**
         * File names and sizes are taken from the Hugging Face listings. Whisper Base, Supertonic
         * and Chatterbox are the exact files tools/audio-test runs.
         */
        val CATALOG = listOf(
            whisper("ggml-base-q5_1.bin", 59_707_625, "Whisper Base", R.string.audio_model_whisper_base),
            whisper("ggml-small-q5_1.bin", 190_085_487, "Whisper Small", R.string.audio_model_whisper_small),
            whisper("ggml-large-v3-turbo-q5_0.bin", 574_041_195, "Whisper Large v3 Turbo", R.string.audio_model_whisper_turbo),
            whisper("ggml-tiny-q5_1.bin", 32_152_673, "Whisper Tiny", R.string.audio_model_whisper_tiny),
            AudioModelSpec(
                "supertonic-3", AudioModelKind.TEXT_TO_SPEECH, "Supertonic 3", R.string.audio_model_supertonic, SUPERTONIC,
                listOf(
                    AudioModelFile("onnx/duration_predictor.onnx", 3_700_147),
                    AudioModelFile("onnx/text_encoder.onnx", 36_416_150),
                    AudioModelFile("onnx/vector_estimator.onnx", 256_534_781),
                    AudioModelFile("onnx/vocoder.onnx", 101_424_195),
                    AudioModelFile("onnx/tts.json", 8_253),
                    AudioModelFile("onnx/unicode_indexer.json", 277_676),
                    AudioModelFile("voice_styles/F1.json", 292_046),
                    AudioModelFile("voice_styles/F2.json", 292_423),
                    AudioModelFile("voice_styles/F3.json", 290_794),
                    AudioModelFile("voice_styles/F4.json", 291_808),
                    AudioModelFile("voice_styles/F5.json", 291_479),
                    AudioModelFile("voice_styles/M1.json", 291_748),
                    AudioModelFile("voice_styles/M2.json", 292_055),
                    AudioModelFile("voice_styles/M3.json", 290_198),
                    AudioModelFile("voice_styles/M4.json", 291_522),
                    AudioModelFile("voice_styles/M5.json", 291_469),
                ),
            ),
            AudioModelSpec(
                "chatterbox-multilingual", AudioModelKind.TEXT_TO_SPEECH, "Chatterbox Multilingual", R.string.audio_model_chatterbox, CHATTERBOX,
                listOf(
                    AudioModelFile("tokenizer.json", 71_798),
                    AudioModelFile("default_voice.wav", 714_320),
                    AudioModelFile("onnx/embed_tokens.onnx", 13_286),
                    AudioModelFile("onnx/embed_tokens.onnx_data", 68_390_912),
                    AudioModelFile("onnx/language_model_q4.onnx", 227_911),
                    AudioModelFile("onnx/language_model_q4.onnx_data", 353_621_248),
                    AudioModelFile("onnx/conditional_decoder.onnx", 6_350_448),
                    AudioModelFile("onnx/conditional_decoder.onnx_data", 533_970_816),
                    AudioModelFile("onnx/speech_encoder.onnx", 1_184_608),
                    AudioModelFile("onnx/speech_encoder.onnx_data", 591_274_880),
                ),
            ),
        )
    }
}
