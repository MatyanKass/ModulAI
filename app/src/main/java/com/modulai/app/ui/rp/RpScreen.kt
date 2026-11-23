package com.modulai.app.ui.rp

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.R
import com.modulai.core.characters.CardError
import com.modulai.core.characters.CardFormatException
import com.modulai.core.characters.CharacterCard
import com.modulai.core.characters.CharacterStore
import com.modulai.core.characters.Persona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Marks the editor as open on a character that does not exist yet. */
private const val NEW_CHARACTER = ""

/**
 * The roleplay module: the character library with its detail and editor screens.
 * [onStartChat] receives the index into [CharacterCard.greetings] of the chosen opening message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpScreen(onBack: () -> Unit, onStartChat: (CharacterCard, Persona, greetingIndex: Int) -> Unit) {
    val context = LocalContext.current
    val store = remember { CharacterStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var cards by remember { mutableStateOf<List<CharacterCard>?>(null) }
    var persona by remember { mutableStateOf(Persona()) }
    // Bumped after every change on disk: reloads the list and invalidates cached avatars.
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPersona by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(revision) { cards = withContext(Dispatchers.IO) { store.list() } }
    LaunchedEffect(Unit) { persona = withContext(Dispatchers.IO) { store.persona() } }

    fun notify(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** Runs a storage operation, showing a failure in the snackbar instead of crashing. */
    fun storage(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(errorText(context, e))
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) storage {
            val card = store.import(uri)
            revision++
            detailId = card.id
            notify(context.getString(R.string.rp_imported, card.name))
        }
    }

    val all = cards
    val detail = all?.firstOrNull { it.id == detailId }
    val editing = when (editorId) {
        null -> null
        NEW_CHARACTER -> remember { CharacterCard() }
        else -> all?.firstOrNull { it.id == editorId }
    }

    when {
        editing != null -> CharacterEditor(
            card = editing,
            version = revision,
            snackbar = snackbar,
            saving = busy,
            onClose = { editorId = null },
            onSave = { draft, avatar, avatarChanged ->
                storage {
                    val saved = withContext(Dispatchers.IO) { store.save(draft) }
                    if (avatarChanged) store.setAvatar(saved.id, avatar)
                    revision++
                    detailId = saved.id
                    editorId = null
                }
            },
        )
        detail != null -> CharacterDetail(
            card = detail,
            persona = persona,
            version = revision,
            snackbar = snackbar,
            onBack = { detailId = null },
            onStart = { index -> onStartChat(detail, persona, index) },
            onEdit = { editorId = detail.id },
            onExport = { target ->
                storage {
                    store.export(detail, target)
                    notify(context.getString(R.string.rp_exported))
                }
            },
            onDelete = {
                storage {
                    withContext(Dispatchers.IO) { store.delete(detail.id) }
                    detailId = null
                    revision++
                }
            },
        )
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.mod_rp)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showPersona = true }) {
                            Icon(Icons.Outlined.Person, stringResource(R.string.rp_persona))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val shown = remember(all, query) {
                val q = query.trim()
                all.orEmpty().filter { card ->
                    q.isEmpty() || card.name.contains(q, ignoreCase = true) || card.tags.any { it.contains(q, ignoreCase = true) }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            // Many file providers report .json as a generic binary file, so that type is
                            // allowed too; the content is validated on import anyway.
                            onClick = { picker.launch(arrayOf("image/png", "application/json", "application/octet-stream")) },
                        ) { Text(stringResource(R.string.rp_import)) }
                        OutlinedButton(onClick = { editorId = NEW_CHARACTER }) { Text(stringResource(R.string.rp_create)) }
                    }
                }
                if (busy) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                when {
                    all == null -> Unit
                    all.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.rp_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.rp_empty_import))
                            Text(stringResource(R.string.rp_empty_create))
                        }
                    }
                    else -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                placeholder = { Text(stringResource(R.string.rp_search)) },
                            )
                        }
                        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.rp_nothing_found))
                        }
                        items(shown, key = { it.id }) { card ->
                            CharacterTile(card, revision, onClick = { detailId = card.id })
                        }
                    }
                }
            }
        }
    }

    if (showPersona) PersonaDialog(
        initial = persona,
        onDismiss = { showPersona = false },
        onSave = { edited ->
            showPersona = false
            persona = edited
            storage { withContext(Dispatchers.IO) { store.savePersona(edited) } }
        },
    )
}

@Composable
private fun CharacterTile(card: CharacterCard, version: Int, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Avatar(card.avatarUri(), version, card.name, AVATAR_SMALL_PX, Modifier.fillMaxWidth().aspectRatio(0.8f))
        Column(Modifier.padding(8.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.tags.isNotEmpty()) {
                Text(
                    card.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PersonaDialog(initial: Persona, onDismiss: () -> Unit, onSave: (Persona) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rp_persona)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rp_persona_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.rp_persona_description)) },
                    supportingText = { Text(stringResource(R.string.rp_persona_help)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Persona(name.trim(), description.trim())) }) { Text(stringResource(R.string.rp_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun errorText(context: Context, e: Exception): String =
    if (e is CardFormatException) {
        context.getString(
            when (e.error) {
                CardError.TOO_LARGE -> R.string.rp_err_too_large
                CardError.BAD_PNG -> R.string.rp_err_bad_png
                CardError.NO_CARD_IN_PNG -> R.string.rp_err_no_card
                CardError.BAD_JSON -> R.string.rp_err_bad_json
                CardError.NOT_A_CARD -> R.string.rp_err_not_card
            },
        )
    } else {
        context.getString(R.string.rp_err_generic, e.message ?: e.javaClass.simpleName)
    }
