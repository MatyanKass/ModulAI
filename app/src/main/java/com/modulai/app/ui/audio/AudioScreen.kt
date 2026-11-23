package com.modulai.app.ui.audio

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.app.audio.AudioHub
import com.modulai.core.runtime.TranscribeOptions

private enum class AudioTab(@StringRes val label: Int) {
    RECOGNIZE(R.string.audio_tab_recognize),
    SPEAK(R.string.audio_tab_speak),
    VOICES(R.string.audio_tab_voices),
    MODELS(R.string.audio_tab_models),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = remember { AudioHub.get(context) }
    var tab by rememberSaveable { mutableStateOf(AudioTab.RECOGNIZE) }
    // Bumped by the download queue whenever a file lands, and by this screen on import and delete.
    val revision by container.downloads.revision.collectAsState()
    val onModelsChanged: () -> Unit = { container.downloads.revision.value++ }
    val openModels = { tab = AudioTab.MODELS }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mod_audio)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                AudioTab.entries.forEach {
                    Tab(selected = tab == it, onClick = { tab = it }, text = { Text(stringResource(it.label), maxLines = 1) })
                }
            }
            when (tab) {
                AudioTab.RECOGNIZE -> SpeechToTextTab(hub, revision, openModels)
                AudioTab.SPEAK -> TextToSpeechTab(hub, revision, openModels)
                AudioTab.VOICES -> VoicesTab(hub, revision, openModels)
                AudioTab.MODELS -> AudioModelsTab(container, hub, revision, onModelsChanged)
            }
        }
    }
}

/** Auto, Russian and English: the languages the app's interface exists in. */
@Composable
internal fun LanguageChips(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGUAGES.forEach { (code, label) ->
            FilterChip(selected = selected == code, enabled = enabled, onClick = { onSelect(code) }, label = { Text(stringResource(label)) })
        }
    }
}

private val LANGUAGES = listOf(
    TranscribeOptions.AUTO to R.string.audio_lang_auto,
    "ru" to R.string.audio_lang_ru,
    "en" to R.string.audio_lang_en,
)

@Composable
internal fun Status(text: String, fraction: Float? = null) {
    Column {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
