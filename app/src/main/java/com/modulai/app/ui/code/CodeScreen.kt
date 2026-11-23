package com.modulai.app.ui.code

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modulai.app.AppContainer
import com.modulai.app.R
import com.modulai.app.code.CodeController
import com.modulai.app.code.CodeError
import com.modulai.app.code.CodeState
import com.modulai.app.ui.RunSettingsSheet
import com.modulai.app.ui.WorkspaceSheet
import com.modulai.core.code.Project
import com.modulai.core.code.Refusal

@Composable
fun CodeScreen(container: AppContainer, onBack: () -> Unit, onPickModel: () -> Unit) {
    val controller = container.code
    val state by controller.state.collectAsState()
    LaunchedEffect(controller) { controller.refresh() }

    val project = state.project
    if (project == null) {
        ProjectList(controller, state, onBack)
    } else {
        ProjectWorkspace(container, controller, state, project, onPickModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectList(controller: CodeController, state: CodeState, onBack: () -> Unit) {
    val context = LocalContext.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    val export = rememberExport(controller)
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            controller.import(archiveName(context, uri)) { context.contentResolver.openInputStream(uri) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mod_code)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { import.launch(ZIP_TYPES) }, enabled = !state.busy) {
                        Icon(Icons.Outlined.FileOpen, stringResource(R.string.code_import_zip))
                    }
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.code_new_project))
                    }
                },
            )
        },
        bottomBar = { StatusBar(controller, state) },
    ) { padding ->
        if (state.projects.isEmpty()) {
            Text(
                stringResource(R.string.code_projects_empty),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(state.projects, key = { it.id }) { project ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { controller.open(project.id) },
                    headlineContent = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(DateUtils.getRelativeTimeSpanString(project.updatedAt).toString()) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.code_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.code_rename)) },
                                    onClick = {
                                        menu = false
                                        renaming = project
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.code_export_zip)) },
                                    onClick = {
                                        menu = false
                                        export(project)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        menu = false
                                        deleting = project
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.code_new_project),
            label = stringResource(R.string.code_project_name),
            initial = "",
            confirm = stringResource(R.string.code_create),
            onDismiss = { creating = false },
            onConfirm = controller::create,
        )
    }
    renaming?.let { project ->
        NameDialog(
            title = stringResource(R.string.code_rename),
            label = stringResource(R.string.code_project_name),
            initial = project.name,
            confirm = stringResource(R.string.code_rename),
            onDismiss = { renaming = null },
            onConfirm = { controller.rename(project.id, it) },
        )
    }
    deleting?.let { project ->
        ConfirmDialog(
            title = stringResource(R.string.code_delete_project_title, project.name),
            body = stringResource(R.string.code_delete_project_body),
            onDismiss = { deleting = null },
            onConfirm = { controller.delete(project.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectWorkspace(
    container: AppContainer,
    controller: CodeController,
    state: CodeState,
    project: Project,
    onPickModel: () -> Unit,
) {
    val chat by container.chat.state.collectAsState()
    var tab by rememberSaveable(project.id) { mutableIntStateOf(TAB_CHAT) }
    var openFile by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val export = rememberExport(controller)

    BackHandler { controller.close() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                chat.model?.id ?: stringResource(R.string.chat_no_model),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { controller.close() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showLog = true }) {
                            Icon(Icons.Outlined.Terminal, stringResource(R.string.workspace_title))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Tune, stringResource(R.string.settings))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.code_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.code_new_conversation)) },
                                    onClick = {
                                        menu = false
                                        controller.newConversation()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.code_export_zip)) },
                                    enabled = !state.busy,
                                    onClick = {
                                        menu = false
                                        export(project)
                                    },
                                )
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    val changes = state.changes.size
                    val titles = listOf(
                        stringResource(R.string.code_tab_chat),
                        stringResource(R.string.code_tab_files),
                        if (changes > 0) stringResource(R.string.code_tab_changes_count, changes) else stringResource(R.string.code_tab_changes),
                        stringResource(R.string.code_tab_preview),
                    )
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                StatusBar(controller, state)
                if (tab == TAB_CHAT) {
                    ChatInput(controller, state, modelReady = chat.model != null, loading = chat.loading, onShowLog = { showLog = true })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                TAB_CHAT -> ChatPane(state, hasModel = chat.model != null, onPickModel = onPickModel, onShowChanges = { tab = TAB_CHANGES })
                TAB_FILES -> FilesPane(controller, state, openFile, onOpen = { openFile = it })
                TAB_CHANGES -> ChangesPane(controller, state)
                else -> PreviewPane(controller, state, project)
            }
        }
    }

    if (showLog) WorkspaceSheet(controller.log, onDismiss = { showLog = false })
    if (showSettings) RunSettingsSheet(container, onDismiss = { showSettings = false })
}

private const val TAB_CHAT = 0
private const val TAB_FILES = 1
private const val TAB_CHANGES = 2

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/** The archive's file name without the extension, as the name of the imported project. */
private fun archiveName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: context.getString(R.string.code_imported_name)
}

/** Asks where to save and writes the project there as a ZIP archive. */
@Composable
private fun rememberExport(controller: CodeController): (Project) -> Unit {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pending
        pending = null
        if (uri != null && id != null) controller.export(id) { context.contentResolver.openOutputStream(uri) }
    }
    return { project ->
        pending = project.id
        launcher.launch("${project.name}.zip")
    }
}

/** Progress of an import or export, and the last error; tapping the error dismisses it. */
@Composable
private fun StatusBar(controller: CodeController, state: CodeState) {
    Column {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Text(
                errorText(error),
                modifier = Modifier.fillMaxWidth().clickable { controller.dismissError() }.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun errorText(error: CodeError): String = when (error) {
    is CodeError.Refused -> refusalText(error.refusal)
    CodeError.PromptTooLong -> stringResource(R.string.chat_too_long)
    is CodeError.Other -> error.message
}

@Composable
internal fun refusalText(refusal: Refusal): String = stringResource(
    when (refusal) {
        Refusal.INVALID_NAME -> R.string.code_err_invalid_name
        Refusal.NAME_TAKEN -> R.string.code_err_name_taken
        Refusal.INVALID_PATH -> R.string.code_err_invalid_path
        Refusal.OUTSIDE_PROJECT -> R.string.code_err_outside
        Refusal.NOT_FOUND -> R.string.code_err_not_found
        Refusal.IS_DIRECTORY -> R.string.code_err_is_directory
        Refusal.ALREADY_EXISTS -> R.string.code_err_exists
        Refusal.FILE_TOO_LARGE -> R.string.code_err_file_too_large
        Refusal.PROJECT_FULL -> R.string.code_err_project_full
        Refusal.TOO_MANY_FILES -> R.string.code_err_too_many_files
        Refusal.BINARY -> R.string.code_err_binary
        Refusal.BAD_ARCHIVE -> R.string.code_err_bad_archive
    },
)

@Composable
internal fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text.trim() != initial,
                onClick = {
                    onConfirm(text.trim())
                    onDismiss()
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, body: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
