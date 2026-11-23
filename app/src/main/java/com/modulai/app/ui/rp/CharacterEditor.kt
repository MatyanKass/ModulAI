package com.modulai.app.ui.rp

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.core.characters.CharacterCard

/**
 * Saved instance state travels through a binder transaction of about 1 MB for the whole app, and a
 * card field can be far longer than a form field usually is. Oversized text is therefore left out:
 * after the process is restored such a field shows the stored card's text again.
 */
private const val MAX_SAVED_CHARS = 20_000

private val BoundedText = Saver<String, String>(save = { it.takeIf { text -> text.length <= MAX_SAVED_CHARS } }, restore = { it })

private val BoundedTexts = Saver<List<String>, ArrayList<String>>(
    save = { list -> ArrayList(list).takeIf { list.sumOf(String::length) <= MAX_SAVED_CHARS } },
    restore = { it },
)

/** Creates or edits [card]; `onSave` gets the edited card and, when `avatarChanged` is set, the new avatar (null removes it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterEditor(
    card: CharacterCard,
    version: Int,
    snackbar: SnackbarHostState,
    saving: Boolean,
    onClose: () -> Unit,
    onSave: (card: CharacterCard, avatar: Uri?, avatarChanged: Boolean) -> Unit,
) {
    var name by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.name) }
    var description by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.description) }
    var personality by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.personality) }
    var scenario by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.scenario) }
    var firstMessage by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.firstMessage) }
    var greetings by rememberSaveable(stateSaver = BoundedTexts) { mutableStateOf(card.alternateGreetings) }
    var examples by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.exampleDialogue) }
    var systemPrompt by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.systemPrompt) }
    var postHistory by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.postHistoryInstructions) }
    var creatorNotes by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.creatorNotes) }
    var tags by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.tags.joinToString(", ")) }
    var creator by rememberSaveable(stateSaver = BoundedText) { mutableStateOf(card.creator) }
    var avatar by rememberSaveable { mutableStateOf<Uri?>(null) }
    var avatarChanged by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val draft = card.copy(
        name = name.trim(),
        description = description,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        alternateGreetings = greetings.filter { it.isNotBlank() },
        exampleDialogue = examples,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistory,
        creatorNotes = creatorNotes,
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        creator = creator.trim(),
    )
    val changed = avatarChanged || draft != card
    val close = { if (changed) confirmDiscard = true else onClose() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatar = uri
            avatarChanged = true
        }
    }
    BackHandler(onBack = close)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (card.id.isEmpty()) R.string.rp_new_character else R.string.rp_edit_character)) },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                },
                actions = {
                    TextButton(
                        enabled = !saving && changed && draft.name.isNotEmpty(),
                        onClick = { onSave(draft, avatar, avatarChanged) },
                    ) { Text(stringResource(R.string.rp_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shownAvatar = if (avatarChanged) avatar else card.avatarUri()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(shownAvatar, version, name, AVATAR_SMALL_PX, Modifier.size(96.dp, 120.dp).clip(RoundedCornerShape(12.dp)))
                Column {
                    OutlinedButton(onClick = { imagePicker.launch("image/*") }) { Text(stringResource(R.string.rp_avatar_choose)) }
                    if (shownAvatar != null) {
                        TextButton(onClick = {
                            avatar = null
                            avatarChanged = true
                        }) { Text(stringResource(R.string.rp_avatar_remove)) }
                    }
                }
            }
            Field(R.string.rp_field_name, name, { name = it }, singleLine = true)
            Field(R.string.rp_field_description, description, { description = it }, minLines = 4, help = R.string.rp_field_description_help)
            Field(R.string.rp_field_personality, personality, { personality = it }, minLines = 2)
            Field(R.string.rp_field_scenario, scenario, { scenario = it }, minLines = 2)
            Field(R.string.rp_field_first_message, firstMessage, { firstMessage = it }, minLines = 3)
            greetings.forEachIndexed { index, text ->
                OutlinedTextField(
                    value = text,
                    onValueChange = { edited -> greetings = greetings.toMutableList().also { it[index] = edited } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rp_field_alt_greeting, index + 1)) },
                    trailingIcon = {
                        IconButton(onClick = { greetings = greetings.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.rp_remove_greeting))
                        }
                    },
                    minLines = 2,
                    maxLines = 12,
                )
            }
            TextButton(onClick = { greetings = greetings + "" }) { Text(stringResource(R.string.rp_add_greeting)) }
            Field(R.string.rp_field_examples, examples, { examples = it }, minLines = 3, help = R.string.rp_field_examples_help)
            Field(R.string.rp_field_system_prompt, systemPrompt, { systemPrompt = it }, minLines = 2, help = R.string.rp_field_system_prompt_help)
            Field(R.string.rp_field_post_history, postHistory, { postHistory = it }, minLines = 2)
            Field(R.string.rp_field_creator_notes, creatorNotes, { creatorNotes = it }, minLines = 2, help = R.string.rp_field_creator_notes_help)
            Field(R.string.rp_field_tags, tags, { tags = it }, singleLine = true)
            Field(R.string.rp_field_creator, creator, { creator = it }, singleLine = true)
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.rp_discard_title)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDiscard = false
                onClose()
            }) { Text(stringResource(R.string.rp_discard)) }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.rp_keep_editing)) } },
    )
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 1,
    @StringRes help: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        supportingText = help?.let { { Text(stringResource(it)) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 12,
    )
}
