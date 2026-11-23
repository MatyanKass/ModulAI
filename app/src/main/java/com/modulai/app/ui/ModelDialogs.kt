package com.modulai.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.core.models.HfApi
import com.modulai.core.models.HfFile
import com.modulai.core.models.HfLink
import com.modulai.core.models.HttpStatusException
import com.modulai.core.models.ModelStore
import com.modulai.core.models.ramFit
import java.io.IOException

/** Paste a Hugging Face link: a file link is ready to download, a repository still needs a file picked. */
@Composable
internal fun LinkDialog(onDismiss: () -> Unit, onFile: (HfLink.File) -> Unit, onRepo: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.link_invalid)
    val noFiles = stringResource(R.string.link_no_gguf)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_from_link)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = { Text(stringResource(R.string.link_hint)) },
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.link_help)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    when (val link = HfLink.parse(text)) {
                        null -> error = invalid
                        is HfLink.File ->
                            if (link.fileName.endsWith(".gguf", ignoreCase = true)) onFile(link) else error = noFiles
                        is HfLink.Repo -> onRepo(link.repo)
                    }
                },
            ) { Text(stringResource(R.string.next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Lists the GGUF files of [repo] and lets the user pick one to download. */
@Composable
internal fun RepoFilesDialog(
    repo: String,
    token: String?,
    totalRam: Long,
    installed: Set<String>,
    onDismiss: () -> Unit,
    onPicked: (HfLink.File) -> Unit,
    onToken: () -> Unit,
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<HfFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    // Keyed by the token too, so adding one from this dialog retries the listing.
    LaunchedEffect(repo, token) {
        files = null
        error = null
        denied = false
        try {
            files = HfApi.listGgufFiles(repo, token)
        } catch (e: IOException) {
            if (e is HttpStatusException && e.accessDenied) denied = true else error = e.message ?: e.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(repo, style = MaterialTheme.typography.titleMedium) },
        text = {
            val found = files
            when {
                denied -> Text(stringResource(R.string.hf_token_needed))
                error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                found == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                found.isEmpty() -> Text(stringResource(R.string.link_no_gguf))
                else -> LazyColumn {
                    items(found, key = { it.path }) { file ->
                        val link = HfLink.File(repo, "main", file.path)
                        val have = ModelStore.sanitize(link.fileName) in installed
                        ListItem(
                            modifier = Modifier.clickable(enabled = !have) { onPicked(link) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(file.path) },
                            supportingContent = {
                                val size = Formatter.formatShortFileSize(context, file.sizeBytes)
                                val note = if (have) R.string.catalog_installed else ramFit(file.sizeBytes, totalRam).label
                                Text("$size · ${stringResource(note)}")
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (denied) TextButton(onClick = onToken) { Text(stringResource(R.string.hf_token)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun TokenDialog(current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    // Deliberately not saveable: the token must not end up in the saved instance state.
    var text by remember { mutableStateOf(current.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hf_token_title)) },
        text = {
            Column {
                Text(stringResource(R.string.hf_token_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.hf_token_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text) }) {
                Text(stringResource(R.string.hf_token_save))
            }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
