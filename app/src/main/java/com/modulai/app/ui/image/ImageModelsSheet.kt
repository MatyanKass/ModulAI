package com.modulai.app.ui.image

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.modulai.app.DownloadItem
import com.modulai.app.DownloadQueue
import com.modulai.app.DownloadState
import com.modulai.app.ModelPrefs
import com.modulai.app.R
import com.modulai.app.image.IMAGE_CATALOG
import com.modulai.app.image.ImageCatalogEntry
import com.modulai.app.image.ImageController
import com.modulai.app.image.ImageModelKind
import com.modulai.app.image.ImageSettings
import com.modulai.app.image.ModelProfile
import com.modulai.core.models.HfLink
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Everything about image model files: what is installed, what is chosen, and getting more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageModelsSheet(controller: ImageController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val settings = state.settings
    val queue by DownloadQueue.items.collectAsState()
    val store = controller.store
    val files = remember(state.modelsRevision) { ImageModelKind.entries.associateWith { store.list(it) } }

    var addKind by rememberSaveable { mutableStateOf(ImageModelKind.CHECKPOINT) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }

    val prefs = remember { ModelPrefs(context) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val download: (HfLink.File, File) -> Unit = { link, target ->
        DownloadQueue.enqueue(context, link, target)
        // The download does not wait for the answer: without the permission it just has no notification.
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
                store.import(uri, addKind)
                controller.modelsChanged()
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                importing = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ImageModelKind.entries.forEach { kind ->
                val installed = files.getValue(kind)
                val offered = IMAGE_CATALOG.filter { entry ->
                    entry.kind == kind && installed.none { it.name == entry.fileName }
                }
                val active = queue.filter { it.target.parentFile == store.dir(kind) }
                if (installed.isEmpty() && offered.isEmpty() && active.isEmpty()) return@forEach

                SectionTitle(stringResource(kind.label))
                KindHint(kind)
                if (kind == ImageModelKind.TAESD || kind == ImageModelKind.VAE) {
                    val chosen = if (kind == ImageModelKind.TAESD) settings.taesd else settings.vae
                    if (installed.isNotEmpty()) {
                        ChoiceRow(stringResource(R.string.img_models_none), null, chosen == null, radio = true) {
                            controller.update(settings.withChoice(kind, null))
                        }
                    }
                }
                installed.forEach { file ->
                    val selected = settings.isChosen(kind, file.name)
                    ChoiceRow(
                        title = file.name,
                        note = Formatter.formatShortFileSize(context, file.length()),
                        selected = selected,
                        radio = kind != ImageModelKind.LORA,
                        onDelete = { toDelete = file },
                    ) { controller.update(settings.withChoice(kind, file.name)) }
                }
                if (kind == ImageModelKind.CHECKPOINT && settings.checkpoint != null) {
                    ProfilePicker(settings) { controller.update(it) }
                }
                if (kind == ImageModelKind.LORA && settings.loras.isNotEmpty()) {
                    Text(
                        stringResource(R.string.img_lora_weight, settings.loraWeight),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.loraWeight,
                        onValueChange = { controller.update(settings.copy(loraWeight = (it * 20).roundToInt() / 20f)) },
                        valueRange = 0f..1.5f,
                    )
                }
                active.forEach { item ->
                    DownloadRow(item, onCancel = { DownloadQueue.cancel(item) }, onRetry = { download(item.file, item.target) })
                }
                offered.filter { entry -> active.none { it.target.name == entry.fileName } }.forEach { entry ->
                    CatalogRow(entry) { download(entry.link, store.fileFor(kind, entry.fileName)) }
                }
            }

            SectionTitle(stringResource(R.string.img_models_add))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageModelKind.entries.forEach { kind ->
                    FilterChip(
                        selected = kind == addKind,
                        onClick = { addKind = kind },
                        label = { Text(stringResource(kind.label)) },
                    )
                }
            }
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showLink = true }) { Text(stringResource(R.string.models_from_link)) }
                OutlinedButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(if (importing) R.string.models_importing else R.string.models_from_file))
                }
            }
        }
    }

    if (showLink) {
        ImageLinkDialog(
            kind = addKind,
            accepts = { store.accepts(addKind, it) },
            onDismiss = { showLink = false },
            onFile = { link ->
                showLink = false
                download(link, store.fileFor(addKind, link.fileName))
            },
        )
    }
    toDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteModel(file)
                    toDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/**
 * Keeps the chosen files in step with what is on disk: a finished download becomes the
 * choice when nothing was chosen, and a choice whose file is gone is dropped.
 */
@Composable
internal fun SyncImageModels(controller: ImageController) {
    val downloads by DownloadQueue.revision.collectAsState()
    val state by controller.state.collectAsState()
    LaunchedEffect(downloads) { controller.modelsChanged() }
    LaunchedEffect(state.modelsRevision) {
        val store = controller.store
        val s = controller.state.value.settings
        fun pick(kind: ImageModelKind, current: String?, auto: Boolean): String? {
            val names = store.list(kind).map { it.name }
            return if (current in names) current else if (auto) names.firstOrNull() else null
        }
        val fixed = s.copy(
            checkpoint = pick(ImageModelKind.CHECKPOINT, s.checkpoint, auto = true),
            upscaler = pick(ImageModelKind.UPSCALER, s.upscaler, auto = true),
            taesd = pick(ImageModelKind.TAESD, s.taesd, auto = false),
            vae = pick(ImageModelKind.VAE, s.vae, auto = false),
            loras = s.loras.intersect(store.list(ImageModelKind.LORA).mapTo(HashSet()) { it.name }),
        )
        if (fixed != s) controller.update(fixed)
    }
}

private fun ImageSettings.isChosen(kind: ImageModelKind, name: String) = when (kind) {
    ImageModelKind.CHECKPOINT -> checkpoint == name
    ImageModelKind.UPSCALER -> upscaler == name
    ImageModelKind.TAESD -> taesd == name
    ImageModelKind.VAE -> vae == name
    ImageModelKind.LORA -> name in loras
}

/** Chooses [name] for [kind]; for LoRA files it toggles membership instead. */
private fun ImageSettings.withChoice(kind: ImageModelKind, name: String?) = when (kind) {
    // The sampling profile belongs to a checkpoint, so a manual override does not carry over.
    ImageModelKind.CHECKPOINT -> copy(checkpoint = name, profile = null)
    ImageModelKind.UPSCALER -> copy(upscaler = name)
    ImageModelKind.TAESD -> copy(taesd = name)
    ImageModelKind.VAE -> copy(vae = name)
    ImageModelKind.LORA -> if (name == null) this else copy(loras = if (name in loras) loras - name else loras + name)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun KindHint(kind: ImageModelKind) {
    val hint = when (kind) {
        ImageModelKind.CHECKPOINT -> R.string.img_kind_checkpoint_hint
        ImageModelKind.UPSCALER -> R.string.img_kind_upscaler_hint
        ImageModelKind.TAESD -> R.string.img_kind_taesd_hint
        ImageModelKind.VAE -> R.string.img_kind_vae_hint
        ImageModelKind.LORA -> R.string.img_kind_lora_hint
    }
    Text(
        stringResource(hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    note: String?,
    selected: Boolean,
    radio: Boolean,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (radio) RadioButton(selected = selected, onClick = onClick) else Checkbox(checked = selected, onCheckedChange = { onClick() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun ProfilePicker(settings: ImageSettings, onChange: (ImageSettings) -> Unit) {
    Text(stringResource(R.string.img_profile), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        stringResource(R.string.img_profile_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelProfile.entries.forEach { profile ->
            FilterChip(
                selected = profile == settings.effectiveProfile(),
                onClick = { onChange(settings.copy(profile = profile)) },
                label = { Text(stringResource(profile.label)) },
            )
        }
    }
}

@Composable
private fun CatalogRow(entry: ImageCatalogEntry, onDownload: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${stringResource(entry.title)} · ${Formatter.formatShortFileSize(context, entry.sizeBytes)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(entry.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, stringResource(R.string.catalog_download)) }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onCancel: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val failed = item.state == DownloadState.FAILED
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.fileName, style = MaterialTheme.typography.bodyLarge)
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
        }
        if (failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
    }
}

/** Paste a Hugging Face link to one file of the kind being added. */
@Composable
private fun ImageLinkDialog(
    kind: ImageModelKind,
    accepts: (String) -> Boolean,
    onDismiss: () -> Unit,
    onFile: (HfLink.File) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.link_invalid)
    val needFile = stringResource(R.string.img_link_need_file)
    val wrongType = stringResource(R.string.img_err_file_type, kind.extensions.joinToString())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(kind.label)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = { Text(stringResource(R.string.img_link_hint)) },
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.img_link_help, kind.extensions.joinToString())) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    when (val link = HfLink.parse(text)) {
                        null -> error = invalid
                        is HfLink.Repo -> error = needFile
                        is HfLink.File -> if (accepts(link.fileName)) onFile(link) else error = wrongType
                    }
                },
            ) { Text(stringResource(R.string.catalog_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
