package com.modulai.app.ui.skills

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.app.skills.Extensions
import com.modulai.core.skills.Skill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SkillsTab(extensions: Extensions, scope: CoroutineScope) {
    val context = LocalContext.current
    val skills by extensions.skills.collectAsState()
    var installing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    fun install(action: suspend () -> Unit) {
        installing = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            } finally {
                installing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) install { installFromFile(context.applicationContext, extensions, uri) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Hint(stringResource(R.string.skills_about)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showLink = true }, enabled = !installing) { Text(stringResource(R.string.skills_from_link)) }
                // Markdown has no dependable MIME type, so the picker shows every file.
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !installing) {
                    Text(stringResource(R.string.skills_from_file))
                }
            }
        }
        if (installing) item {
            Text(stringResource(R.string.skills_installing))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (skills.isEmpty() && !installing) item {
            Text(stringResource(R.string.skills_empty), modifier = Modifier.padding(top = 16.dp))
        }
        items(skills, key = { it.id }) { skill ->
            ListItem(
                modifier = Modifier.clickable { openId = skill.id },
                headlineContent = { Text(skill.name) },
                supportingContent = {
                    Column {
                        Text(skill.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (skill.builtIn) Hint(stringResource(R.string.skills_built_in))
                    }
                },
                trailingContent = {
                    Switch(checked = skill.enabled, onCheckedChange = { on -> scope.launch { extensions.setSkillEnabled(skill.id, on) } })
                },
            )
        }
        item {
            TextButton(onClick = { scope.launch { extensions.restoreBuiltInSkills() } }) {
                Text(stringResource(R.string.skills_restore))
            }
        }
    }

    if (showLink) LinkDialog(
        onDismiss = { showLink = false },
        onInstall = { link ->
            showLink = false
            install { extensions.installSkillFromLink(link) }
        },
    )
    skills.find { it.id == openId }?.let { skill ->
        SkillDialog(skill, onDismiss = { openId = null }, onDelete = {
            openId = null
            deleteId = skill.id
        })
    }
    skills.find { it.id == deleteId }?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { Text(skill.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleteId = null
                    scope.launch { extensions.deleteSkill(skill.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    failure?.let {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.skills_install_failed)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

private suspend fun installFromFile(context: Context, extensions: Extensions, uri: Uri) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val stream = resolver.openInputStream(uri) ?: error("Cannot open the file")
    stream.use { extensions.installSkill(it, fileName?.substringBeforeLast('.')?.takeIf { name -> name.isNotBlank() } ?: "skill") }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_link_title)) },
        text = {
            Column {
                Text(stringResource(R.string.skills_link_body))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.skills_link_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = link.isNotBlank(), onClick = { onInstall(link) }) {
                Text(stringResource(R.string.skills_link_install))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SkillDialog(skill: Skill, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (skill.tools.isNotEmpty()) Hint(stringResource(R.string.skills_needs_tools, skill.tools.joinToString(", ")))
                if (skill.files.isNotEmpty()) Hint(stringResource(R.string.skills_files, skill.files.joinToString(", ")))
                SelectionContainer { Text(skill.instructions, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) } },
    )
}
