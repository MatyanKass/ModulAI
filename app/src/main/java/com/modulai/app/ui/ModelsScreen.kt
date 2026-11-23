package com.modulai.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.DownloadItem
import com.modulai.app.DownloadState
import com.modulai.app.ModelPrefs
import com.modulai.app.R
import com.modulai.core.models.Catalog
import com.modulai.core.models.CatalogRepository
import com.modulai.core.models.HfLink
import com.modulai.core.models.RamFit
import com.modulai.core.models.ramFit
import com.modulai.core.runtime.ModelDescriptor
import kotlinx.coroutines.launch
import java.io.File

private enum class Section(@StringRes val label: Int) {
    INSTALLED(R.string.models_installed),
    CATALOG(R.string.models_catalog),
    SEARCH(R.string.models_search),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ModelPrefs(context) }
    val chat by container.chat.state.collectAsState()
    val queue by container.downloads.queue.collectAsState()
    val revision by container.downloads.revision.collectAsState()
    val models = remember(revision) { container.models.list() }
    val installed = remember(models) { models.mapTo(HashSet()) { File(it.path).name } }
    val totalRam = remember { container.device.inspect().memory.totalBytes }

    var section by rememberSaveable { mutableStateOf(Section.INSTALLED) }
    var token by remember { mutableStateOf(prefs.hfToken) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var browseRepo by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ModelDescriptor?>(null) }
    val search = remember { HfSearchState() }

    var catalog by remember { mutableStateOf<Catalog?>(null) }
    LaunchedEffect(Unit) {
        val repository = CatalogRepository(context)
        catalog = repository.load()
        catalog = repository.refresh()
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val download: (HfLink.File) -> Unit = { file ->
        // The download does not wait for the answer: without the permission it just has no notification.
        container.downloads.enqueue(context, file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.notificationsAsked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.notificationsAsked = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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

    val downloads: LazyListScope.() -> Unit = {
        items(queue, key = { "download-${it.id}" }) { item ->
            DownloadCard(
                item,
                onCancel = { container.downloads.cancel(item) },
                onRetry = { download(item.file) },
                onToken = { showToken = true },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryTabRow(selectedTabIndex = section.ordinal, modifier = Modifier.weight(1f)) {
                Section.entries.forEach {
                    Tab(
                        selected = section == it,
                        onClick = { section = it },
                        text = { Text(stringResource(it.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            IconButton(onClick = { showToken = true }) {
                Icon(Icons.Outlined.Key, stringResource(R.string.hf_token))
            }
        }
        when (section) {
            Section.INSTALLED -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showLink = true }) { Text(stringResource(R.string.models_from_link)) }
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                            Text(stringResource(R.string.models_from_file))
                        }
                    }
                }
                if (importing) item {
                    Text(stringResource(R.string.models_importing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                downloads()
                if (models.isEmpty() && queue.isEmpty()) item {
                    Text(stringResource(R.string.models_empty), modifier = Modifier.padding(top = 16.dp))
                }
                items(models, key = { it.path }) { model ->
                    ListItem(
                        modifier = Modifier.clickable { container.chat.select(model) },
                        leadingContent = {
                            RadioButton(
                                selected = chat.model?.path == model.path,
                                onClick = { container.chat.select(model) },
                            )
                        },
                        headlineContent = { Text(model.id) },
                        supportingContent = {
                            val size = Formatter.formatShortFileSize(context, model.sizeBytes)
                            Text("$size · ${stringResource(ramFit(model.sizeBytes, totalRam).label)}")
                        },
                        trailingContent = {
                            IconButton(onClick = { toDelete = model }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                            }
                        },
                    )
                }
            }
            Section.CATALOG -> CatalogSection(catalog, installed, queue, totalRam, prefs, download, downloads)
            Section.SEARCH -> HfSearchSection(
                state = search,
                onSearch = { search.run(scope, token) },
                onRepo = { browseRepo = it },
                header = downloads,
            )
        }
    }

    if (showLink) LinkDialog(
        onDismiss = { showLink = false },
        onFile = {
            showLink = false
            download(it)
        },
        onRepo = {
            showLink = false
            browseRepo = it
        },
    )
    browseRepo?.let { repo ->
        RepoFilesDialog(
            repo = repo,
            token = token,
            totalRam = totalRam,
            installed = installed,
            onDismiss = { browseRepo = null },
            onPicked = {
                browseRepo = null
                download(it)
            },
            onToken = { showToken = true },
        )
    }
    if (showToken) TokenDialog(
        current = token,
        onDismiss = { showToken = false },
        onSave = {
            prefs.hfToken = it
            token = prefs.hfToken
            showToken = false
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

internal val RamFit.label: Int
    @StringRes get() = when (this) {
        RamFit.FITS -> R.string.fit_ok
        RamFit.TIGHT -> R.string.fit_tight
        RamFit.TOO_LARGE -> R.string.fit_no
    }

@Composable
private fun DownloadCard(item: DownloadItem, onCancel: () -> Unit, onRetry: () -> Unit, onToken: () -> Unit) {
    val context = LocalContext.current
    val failed = item.state == DownloadState.FAILED
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
            Text(item.fileName, style = MaterialTheme.typography.titleSmall)
            val p = item.progress
            when {
                failed -> Text(
                    if (item.accessDenied) stringResource(R.string.hf_token_needed) else item.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                item.state == DownloadState.WAITING ->
                    Text(stringResource(R.string.download_waiting), style = MaterialTheme.typography.bodySmall)
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
            Row {
                if (failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
                if (item.accessDenied) TextButton(onClick = onToken) { Text(stringResource(R.string.hf_token)) }
                TextButton(onClick = onCancel) {
                    Text(stringResource(if (failed) R.string.delete else R.string.cancel))
                }
            }
        }
    }
}
