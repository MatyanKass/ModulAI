package com.modulai.app.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.core.models.HfApi
import com.modulai.core.models.HfFile
import com.modulai.core.models.HfLink
import com.modulai.core.runtime.ModelDescriptor
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat by container.chat.state.collectAsState()
    val download by container.downloads.active.collectAsState()
    val revision by container.downloads.revision.collectAsState()
    val models = remember(revision) { container.models.list() }
    val totalRam = remember { container.device.inspect().memory.totalBytes }

    var showLink by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ModelDescriptor?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                container.models.import(uri)
                container.downloads.revision.value++
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                importing = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showLink = true }, enabled = download?.progress == null || download?.error != null) {
                    Text(stringResource(R.string.models_from_link))
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                    Text(stringResource(R.string.models_from_file))
                }
            }
        }
        if (importing) item {
            Text(stringResource(R.string.models_importing))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        download?.let { d ->
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.fileName, style = MaterialTheme.typography.titleSmall)
                        val p = d.progress
                        when {
                            d.error != null -> Text(d.error, color = MaterialTheme.colorScheme.error)
                            p == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            else -> {
                                LinearProgressIndicator(
                                    progress = { p.fraction },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                )
                                Text(
                                    "${Formatter.formatShortFileSize(context, p.doneBytes)} / " +
                                        Formatter.formatShortFileSize(context, p.totalBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        TextButton(onClick = { container.downloads.cancel() }) {
                            Text(stringResource(if (d.error != null) R.string.close else R.string.cancel))
                        }
                    }
                }
            }
        }
        if (models.isEmpty() && download == null) item {
            Text(stringResource(R.string.models_empty), modifier = Modifier.padding(top = 24.dp))
        }
        items(models, key = { it.path }) { model ->
            val selected = chat.model?.path == model.path
            ListItem(
                modifier = Modifier.clickable { container.chat.select(model) },
                leadingContent = { RadioButton(selected = selected, onClick = { container.chat.select(model) }) },
                headlineContent = { Text(model.id) },
                supportingContent = {
                    val size = Formatter.formatShortFileSize(context, model.sizeBytes)
                    // Weights plus roughly 1.5 GB for context, Android and the app itself.
                    val need = model.sizeBytes + 1_500_000_000L
                    val fit = when {
                        need > totalRam -> R.string.fit_no
                        need > totalRam * 0.7 -> R.string.fit_tight
                        else -> R.string.fit_ok
                    }
                    Text("$size · ${stringResource(fit)}")
                },
                trailingContent = {
                    IconButton(onClick = { toDelete = model }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                    }
                },
            )
        }
    }

    if (showLink) LinkDialog(
        onDismiss = { showLink = false },
        onPicked = {
            showLink = false
            container.downloads.start(it)
        },
    )
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.close)) } },
            text = { Text(it) },
        )
    }
    toDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(model.id) },
            confirmButton = {
                TextButton(onClick = {
                    if (chat.model?.path == model.path) container.chat.select(null)
                    container.models.delete(model)
                    container.downloads.revision.value++
                    toDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Paste a Hugging Face link; a repository link leads to a choice of its GGUF files. */
@Composable
private fun LinkDialog(onDismiss: () -> Unit, onPicked: (HfLink.File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<HfFile>>(emptyList()) }
    val invalid = stringResource(R.string.link_invalid)
    val noFiles = stringResource(R.string.link_no_gguf)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_from_link)) },
        text = {
            Column {
                if (repo == null) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        label = { Text(stringResource(R.string.link_hint)) },
                        isError = error != null,
                        supportingText = { Text(error ?: stringResource(R.string.link_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LazyColumn {
                        items(files) { file ->
                            ListItem(
                                modifier = Modifier.clickable { onPicked(HfLink.File(repo!!, "main", file.path)) },
                                headlineContent = { Text(file.path) },
                                supportingContent = { Text(Formatter.formatShortFileSize(context, file.sizeBytes)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (repo == null) TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    when (val link = HfLink.parse(text)) {
                        null -> error = invalid
                        is HfLink.File ->
                            if (link.fileName.endsWith(".gguf", ignoreCase = true)) onPicked(link) else error = noFiles
                        is HfLink.Repo -> {
                            busy = true
                            scope.launch {
                                try {
                                    val found = HfApi.listGgufFiles(link.repo)
                                    if (found.isEmpty()) error = noFiles else {
                                        files = found
                                        repo = link.repo
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: e.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
