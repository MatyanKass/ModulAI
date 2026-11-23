package com.modulai.app.ui.audio

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.app.audio.AudioException
import com.modulai.app.audio.AudioFileDecoder
import com.modulai.app.audio.AudioHub
import com.modulai.app.audio.audioErrorMessage
import com.modulai.app.audio.hasMicPermission
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.SpeechEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Longest recording from the microphone, and longest part of a file that is read. */
private const val MAX_RECORD_SECONDS = 600
private const val MAX_FILE_SECONDS = 1800

@Composable
internal fun SpeechToTextTab(hub: AudioHub, revision: Int, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val models = remember(revision) { hub.models.speechToTextModels() }
    val listening by hub.listening.collectAsState()
    val level by hub.micLevel.collectAsState()

    var modelPath by remember(models) { mutableStateOf(hub.speechModel()?.path) }
    var language by remember { mutableStateOf(hub.recognitionLanguage) }
    var translate by rememberSaveable { mutableStateOf(false) }
    var transcript by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job?.isActive == true

    fun recognize(source: suspend () -> Pcm) {
        error = null
        notice = null
        transcript = ""
        job = scope.launch {
            try {
                val pcm = source()
                progress = 0
                hub.transcribe(pcm, language, translate) { event ->
                    when (event) {
                        is SpeechEvent.Progress -> progress = event.percent
                        is SpeechEvent.Segment -> transcript = (transcript + " " + event.segment.text).trim()
                        is SpeechEvent.Done -> transcript = event.text
                    }
                }
                if (transcript.isBlank()) notice = context.getString(R.string.audio_no_speech_found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = audioErrorMessage(context, e)
            } finally {
                progress = null
                decoding = false
                job = null
            }
        }
    }

    val record = { recognize { hub.record(MAX_RECORD_SECONDS, stopOnSilence = false) } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else error = audioErrorMessage(context, AudioException(AudioException.Reason.NO_MIC_PERMISSION))
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) recognize {
            decoding = true
            val decoded = AudioFileDecoder.decode(context, uri, MAX_FILE_SECONDS)
            decoding = false
            if (decoded.truncated) notice = context.getString(R.string.audio_file_truncated, MAX_FILE_SECONDS / 60)
            decoded.pcm
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (models.isEmpty()) {
            Text(stringResource(R.string.audio_need_speech_model), modifier = Modifier.padding(top = 16.dp))
            Button(onClick = onOpenModels) { Text(stringResource(R.string.audio_tab_models)) }
            return@Column
        }
        if (models.size > 1) {
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                models.forEach { model ->
                    FilterChip(
                        selected = modelPath == model.path,
                        enabled = !busy,
                        onClick = {
                            modelPath = model.path
                            hub.speechModelPath = model.path
                        },
                        label = { Text(model.id) },
                    )
                }
            }
        }
        LanguageChips(language, enabled = !busy) {
            language = it
            hub.recognitionLanguage = it
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = translate, onCheckedChange = { translate = it }, enabled = !busy)
            Text(stringResource(R.string.audio_translate))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                listening -> Button(onClick = { hub.stopListening() }) {
                    Icon(Icons.Outlined.Stop, null)
                    Text(stringResource(R.string.audio_stop_recording), Modifier.padding(start = 8.dp))
                }
                busy -> OutlinedButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.cancel)) }
                else -> {
                    Button(onClick = { if (hasMicPermission(context)) record() else askMic.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Icon(Icons.Outlined.Mic, null)
                        Text(stringResource(R.string.audio_record), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("audio/*", "video/*")) }) {
                        Icon(Icons.Outlined.AudioFile, null)
                        Text(stringResource(R.string.audio_from_file), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        when {
            listening -> {
                Text(stringResource(R.string.audio_listening), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
            }
            decoding -> Status(stringResource(R.string.audio_reading_file))
            busy -> progress.let { p ->
                // Whisper reports nothing until the first half-minute of sound is done.
                if (p == null || p == 0) Status(stringResource(R.string.audio_recognizing))
                else Status(stringResource(R.string.audio_recognizing_percent, p), p / 100f)
            }
        }
        error?.let { ErrorText(it) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (transcript.isNotBlank()) {
            SelectionContainer { Text(transcript) }
            Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(transcript)) }) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Text(stringResource(R.string.audio_copy), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcript)
                    context.startActivity(Intent.createChooser(send, null))
                }) {
                    Icon(Icons.Outlined.Share, null)
                    Text(stringResource(R.string.audio_share), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
