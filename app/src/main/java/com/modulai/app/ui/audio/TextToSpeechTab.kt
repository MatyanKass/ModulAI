package com.modulai.app.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.modulai.app.audio.AudioHub
import com.modulai.app.audio.SpeakState
import com.modulai.app.audio.VoiceOption
import com.modulai.app.audio.audioErrorMessage
import com.modulai.app.audio.saveWavToMusic
import com.modulai.core.runtime.VoiceKind
import com.modulai.engine.audio.OnnxTtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun voiceLabel(option: VoiceOption): String {
    val voice = option.voice
    return when {
        voice == null -> stringResource(R.string.audio_voice_system_default)
        option.isSystem -> stringResource(R.string.audio_voice_system, voice.name)
        voice.kind == VoiceKind.CLONED -> stringResource(R.string.audio_voice_cloned, voice.name)
        voice.name == OnnxTtsEngine.STOCK_VOICE -> "${option.modelTitle} · ${stringResource(R.string.audio_voice_stock)}"
        else -> "${option.modelTitle} · ${voice.name}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextToSpeechTab(hub: AudioHub, revision: Int, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by hub.speakState.collectAsState()
    val speakError by hub.speakError.collectAsState()
    val lastSpeech by hub.lastSpeech.collectAsState()

    var options by remember { mutableStateOf<List<VoiceOption>?>(null) }
    var selected by remember { mutableStateOf<VoiceOption?>(null) }
    LaunchedEffect(revision) {
        val found = hub.voiceOptions()
        options = found
        selected = hub.pickVoice(found)
    }

    var text by rememberSaveable { mutableStateOf("") }
    var language by remember { mutableStateOf(hub.speechLanguage) }
    var speed by remember { mutableFloatStateOf(hub.speed) }
    var menuOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val voices = options
        if (voices == null) {
            Status(stringResource(R.string.audio_loading_voices))
            return@Column
        }
        if (voices.isEmpty()) {
            Text(stringResource(R.string.audio_need_voice), modifier = Modifier.padding(top = 16.dp))
            Button(onClick = onOpenModels) { Text(stringResource(R.string.audio_tab_models)) }
            return@Column
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.audio_text_hint)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            minLines = 3,
            maxLines = 10,
        )

        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = selected?.let { voiceLabel(it) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.audio_voice)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                voices.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(voiceLabel(option)) },
                        onClick = {
                            selected = option
                            hub.voiceKey = option.key
                            menuOpen = false
                        },
                    )
                }
            }
        }
        if (voices.all { it.isSystem }) {
            Text(
                stringResource(R.string.audio_only_system_voices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LanguageChips(language, enabled = true) {
            language = it
            hub.speechLanguage = it
        }

        val adjustable = selected?.adjustableSpeed != false
        Text(
            if (adjustable) stringResource(R.string.audio_speed, speed) else stringResource(R.string.audio_speed_fixed),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = speed,
            onValueChange = { speed = it },
            onValueChangeFinished = { hub.speed = speed },
            valueRange = 0.5f..2f,
            steps = 14,
            enabled = adjustable,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == SpeakState.IDLE) {
                Button(
                    onClick = {
                        message = null
                        saveError = null
                        hub.speak(text, selected)
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Text(stringResource(R.string.audio_speak), Modifier.padding(start = 8.dp))
                }
            } else {
                Button(onClick = { hub.stopSpeaking() }) {
                    Icon(Icons.Outlined.Stop, null)
                    Text(stringResource(R.string.chat_stop), Modifier.padding(start = 8.dp))
                }
            }
            val speech = lastSpeech
            if (speech != null && state == SpeakState.IDLE) {
                OutlinedButton(onClick = { hub.replay() }) { Icon(Icons.Outlined.Replay, stringResource(R.string.audio_replay)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            message = context.getString(R.string.audio_saved, saveWavToMusic(context, speech, "modulai-$stamp"))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saveError = audioErrorMessage(context, e)
                        }
                    }
                }) {
                    Icon(Icons.Outlined.Save, null)
                    Text(stringResource(R.string.audio_save_wav), Modifier.padding(start = 8.dp))
                }
            }
        }

        if (state == SpeakState.PREPARING) Status(stringResource(R.string.audio_synthesizing))
        (speakError?.let { audioErrorMessage(context, it) } ?: saveError)?.let { ErrorText(it) }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
