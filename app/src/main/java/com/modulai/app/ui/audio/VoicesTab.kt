package com.modulai.app.ui.audio

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.app.audio.AudioException
import com.modulai.app.audio.AudioFileDecoder
import com.modulai.app.audio.AudioHub
import com.modulai.app.audio.SpeakState
import com.modulai.app.audio.VoiceOption
import com.modulai.app.audio.audioErrorMessage
import com.modulai.app.audio.hasMicPermission
import com.modulai.core.runtime.Pcm
import com.modulai.core.runtime.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun VoicesTab(hub: AudioHub, revision: Int, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(revision) { hub.cloningModel() }
    val referenceSeconds = remember(model) { hub.cloneReferenceSeconds() }
    val listening by hub.listening.collectAsState()
    val level by hub.micLevel.collectAsState()
    val speakState by hub.speakState.collectAsState()
    val speakError by hub.speakError.collectAsState()

    var changes by remember { mutableIntStateOf(0) }
    val voices = remember(changes, revision) { hub.clonedVoices.list() }
    var name by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var toDelete by remember { mutableStateOf<Voice?>(null) }
    val busy = job?.isActive == true

    fun create(sample: suspend () -> Pcm) {
        error = null
        job = scope.launch {
            try {
                val reference = sample()
                creating = true
                hub.cloneVoice(reference, name.trim().ifEmpty { context.getString(R.string.audio_voice_default_name, voices.size + 1) })
                name = ""
                changes++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = audioErrorMessage(context, e)
            } finally {
                creating = false
                job = null
            }
        }
    }

    // A little more than the model uses, so that a slow start does not eat into the sample.
    val record = { create { hub.record(referenceSeconds + 5, stopOnSilence = false) } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else error = audioErrorMessage(context, AudioException(AudioException.Reason.NO_MIC_PERMISSION))
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) create { AudioFileDecoder.decode(context, uri, referenceSeconds + 5).pcm }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (model == null) {
            Text(stringResource(R.string.audio_need_cloning_model), modifier = Modifier.padding(top = 16.dp))
            Button(onClick = onOpenModels) { Text(stringResource(R.string.audio_tab_models)) }
            return@Column
        }
        Text(stringResource(R.string.audio_clone_help, referenceSeconds), modifier = Modifier.padding(top = 16.dp))
        Text(
            stringResource(R.string.audio_clone_consent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.audio_voice_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
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
                        Text(stringResource(R.string.audio_record_sample), Modifier.padding(start = 8.dp))
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
            creating -> Status(stringResource(R.string.audio_creating_voice))
        }
        (error ?: speakError?.let { audioErrorMessage(context, it) })?.let { ErrorText(it) }

        if (voices.isEmpty()) {
            Text(stringResource(R.string.audio_no_cloned_voices), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        voices.forEach { voice ->
            ListItem(
                headlineContent = { Text(voice.name) },
                trailingContent = {
                    Row {
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                if (speakState != SpeakState.IDLE) {
                                    hub.stopSpeaking()
                                } else {
                                    val option = VoiceOption(model.path, model.id, voice, emptySet(), adjustableSpeed = false)
                                    hub.speak(context.getString(R.string.audio_voice_test_phrase), option)
                                }
                            },
                        ) {
                            if (speakState == SpeakState.IDLE) Icon(Icons.Outlined.PlayArrow, stringResource(R.string.audio_voice_test))
                            else Icon(Icons.Outlined.Stop, stringResource(R.string.chat_stop))
                        }
                        IconButton(onClick = { toDelete = voice }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                    }
                },
            )
        }
        if (speakState == SpeakState.PREPARING) Status(stringResource(R.string.audio_synthesizing))
    }

    toDelete?.let { voice ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.audio_delete_voice_title)) },
            text = { Text(voice.name) },
            confirmButton = {
                TextButton(onClick = {
                    hub.clonedVoices.delete(voice)
                    changes++
                    toDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
