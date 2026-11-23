package com.modulai.app.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.ModelPrefs
import com.modulai.app.R
import com.modulai.app.audio.AudioDownload
import com.modulai.app.audio.AudioHub
import com.modulai.app.audio.AudioModelKind
import com.modulai.app.audio.AudioModelSpec
import com.modulai.app.audio.AudioModelStore
import com.modulai.core.runtime.ModelDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun AudioModelsTab(container: AppContainer, hub: AudioHub, revision: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ModelPrefs(context) }
    val queue by container.downloads.queue.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Models that are on disk but not in the built-in list came from a file.
    val imported = remember(revision) {
        val known = AudioModelStore.CATALOG.flatMapTo(HashSet()) { spec -> spec.files.map { it.name } + spec.id }
        (hub.models.speechToTextModels() + hub.models.textToSpeechModels()).filter { File(it.path).name !in known }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val download: (AudioModelSpec) -> Unit = { spec ->
        hub.models.download(spec)
        // The download does not wait for the answer: without the permission it just has no notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.notificationsAsked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.notificationsAsked = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val delete: (path: String, remove: () -> Unit) -> Unit = { path, remove ->
        scope.launch {
            hub.unload(path)
            remove()
            onChanged()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        error = null
        scope.launch {
            try {
                hub.models.import(uri)
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                importing = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (kind in AudioModelKind.entries) {
            item(key = "header-$kind") {
                Text(
                    stringResource(if (kind == AudioModelKind.SPEECH_TO_TEXT) R.string.audio_models_recognition else R.string.audio_models_synthesis),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(AudioModelStore.CATALOG.filter { it.kind == kind }, key = { it.id }) { spec ->
                // Reading `revision` here makes the card notice files that have just arrived.
                val installed = remember(revision, queue) { hub.models.isInstalled(spec) }
                SpecCard(
                    spec = spec,
                    installed = installed,
                    progress = if (installed) null else hub.models.downloadOf(spec, queue),
                    onDownload = { download(spec) },
                    onCancel = { hub.models.cancelDownload(spec, queue) },
                    onDelete = { delete(hub.models.pathOf(spec)) { hub.models.delete(spec) } },
                )
            }
        }
        item(key = "import") {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.models_from_file))
            }
            Text(
                stringResource(R.string.audio_import_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (importing) Status(stringResource(R.string.models_importing))
            error?.let { ErrorText(it) }
        }
        items(imported, key = { it.path }) { model ->
            ImportedItem(model, onDelete = { delete(model.path) { hub.models.delete(model) } })
        }
    }
}

@Composable
private fun SpecCard(
    spec: AudioModelSpec,
    installed: Boolean,
    progress: AudioDownload?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
            Text(spec.title, style = MaterialTheme.typography.titleSmall)
            Text(
                Formatter.formatShortFileSize(context, spec.sizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(spec.description), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (progress != null) {
                if (progress.failed) {
                    ErrorText(progress.error.orEmpty())
                } else {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(
                        "${Formatter.formatShortFileSize(context, progress.doneBytes)} / " +
                            Formatter.formatShortFileSize(context, progress.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row {
                when {
                    installed -> {
                        Text(
                            stringResource(R.string.audio_model_installed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                    }
                    progress == null -> TextButton(onClick = onDownload) { Text(stringResource(R.string.audio_model_download)) }
                    else -> {
                        if (progress.failed) TextButton(onClick = onDownload) { Text(stringResource(R.string.download_retry)) }
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedItem(model: ModelDescriptor, onDelete: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(model.id) },
        supportingContent = { Text(Formatter.formatShortFileSize(context, model.sizeBytes)) },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) } },
    )
}
