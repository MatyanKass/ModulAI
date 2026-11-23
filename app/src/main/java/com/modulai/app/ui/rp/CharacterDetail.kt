package com.modulai.app.ui.rp

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.core.characters.CharacterCard
import com.modulai.core.characters.Persona
import com.modulai.core.characters.applyMacros

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterDetail(
    card: CharacterCard,
    persona: Persona,
    version: Int,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onStart: (greetingIndex: Int) -> Unit,
    onEdit: () -> Unit,
    onExport: (Uri) -> Unit,
    onDelete: () -> Unit,
) {
    var greetingIndex by rememberSaveable(card.id) { mutableIntStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onExport(uri)
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.rp_edit)) }
                    IconButton(onClick = { exporter.launch(exportFileName(card.name)) }) {
                        Icon(Icons.Outlined.FileUpload, stringResource(R.string.rp_export))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(
                    card.avatarUri(), version, card.name, AVATAR_LARGE_PX,
                    Modifier.size(120.dp, 150.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.name, style = MaterialTheme.typography.headlineSmall)
                    if (card.creator.isNotBlank()) {
                        Text(
                            stringResource(R.string.rp_by_creator, card.creator),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (card.tags.isNotEmpty()) {
                        Text(
                            card.tags.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (card.lorebook.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.rp_lorebook_entries, card.lorebook.size, card.lorebook.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(onClick = { onStart(greetingIndex) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rp_start_chat))
            }
            val greetings = card.greetings
            if (greetings.size > 1) {
                Column {
                    Text(stringResource(R.string.rp_greeting_pick), style = MaterialTheme.typography.titleSmall)
                    greetings.forEachIndexed { index, text ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = index == greetingIndex, role = Role.RadioButton) { greetingIndex = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == greetingIndex, onClick = null, modifier = Modifier.padding(end = 12.dp))
                            Text(
                                applyMacros(text.trim(), card, persona),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Section(stringResource(R.string.rp_creator_notes), card.creatorNotes)
            Section(stringResource(R.string.rp_field_description), applyMacros(card.description, card, persona))
            Section(stringResource(R.string.rp_field_scenario), applyMacros(card.scenario, card, persona))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.rp_delete_title)) },
        text = { Text(stringResource(R.string.rp_delete_body, card.name)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                onDelete()
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String, text: String) {
    if (text.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text.trim(), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun exportFileName(name: String): String =
    name.replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim().ifEmpty { "character" } + ".json"
