package com.modulai.app.ui.code

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modulai.app.R
import com.modulai.app.code.CodeController
import com.modulai.app.code.CodeState
import com.modulai.app.code.Draft
import com.modulai.core.code.FileEntry
import com.modulai.core.code.Refusal
import com.modulai.core.code.WorkspaceException

/** One text field holds the whole file, so beyond this size typing would stutter. */
private const val MAX_EDITABLE_BYTES = 200 * 1024

@Composable
internal fun FilesPane(controller: CodeController, state: CodeState, openFile: String?, onOpen: (String?) -> Unit) {
    val entry = state.files.find { it.path == openFile }
    if (openFile != null && entry != null) {
        BackHandler { onOpen(null) }
        Editor(controller, entry, state.revision, onClose = { onOpen(null) })
    } else {
        // The open file was deleted or moved, by the coder or from the tree.
        if (openFile != null) LaunchedEffect(openFile) { onOpen(null) }
        FileTree(controller, state.files, onOpen)
    }
}

private sealed interface Node {
    val name: String
    val depth: Int

    data class Folder(val path: String, override val name: String, override val depth: Int) : Node
    data class Leaf(val file: FileEntry, override val name: String, override val depth: Int) : Node
}

/** Turns the sorted flat list of paths into tree rows, leaving out what is inside collapsed folders. */
private fun treeRows(files: List<FileEntry>, collapsed: Set<String>): List<Node> {
    val rows = ArrayList<Node>()
    val seen = HashSet<String>()
    for (file in files) {
        val parts = file.path.split('/')
        var folder = ""
        var hidden = false
        for (depth in 0 until parts.lastIndex) {
            folder = if (folder.isEmpty()) parts[depth] else "$folder/${parts[depth]}"
            if (!hidden && seen.add(folder)) rows += Node.Folder(folder, parts[depth], depth)
            if (folder in collapsed) hidden = true
        }
        if (!hidden) rows += Node.Leaf(file, parts.last(), parts.lastIndex)
    }
    return rows
}

@Composable
private fun FileTree(controller: CodeController, files: List<FileEntry>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(files, collapsed) { treeRows(files, collapsed.toSet()) }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.code_new_file), modifier = Modifier.padding(start = 8.dp))
        }
        if (files.isEmpty()) {
            Text(
                stringResource(R.string.code_files_empty),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { if (it is Node.Folder) "d:${it.path}" else "f:${(it as Node.Leaf).file.path}" }) { node ->
                when (node) {
                    is Node.Folder -> {
                        val closed = node.path in collapsed
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { collapsed = if (closed) collapsed - node.path else collapsed + node.path }
                                .padding(start = (12 + node.depth * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (closed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(node.name, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    is Node.Leaf -> {
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(node.file.path) }
                                .padding(start = (12 + node.depth * 16).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                node.name,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Formatter.formatShortFileSize(context, node.file.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.code_more))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.code_move)) },
                                        onClick = {
                                            menu = false
                                            moving = node.file.path
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete)) },
                                        onClick = {
                                            menu = false
                                            deleting = node.file.path
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.code_new_file),
            label = stringResource(R.string.code_file_path),
            initial = "",
            confirm = stringResource(R.string.code_create),
            onDismiss = { creating = false },
            onConfirm = controller::createFile,
        )
    }
    moving?.let { path ->
        NameDialog(
            title = stringResource(R.string.code_move),
            label = stringResource(R.string.code_file_path),
            initial = path,
            confirm = stringResource(R.string.code_move),
            onDismiss = { moving = null },
            onConfirm = { controller.moveFile(path, it) },
        )
    }
    deleting?.let { path ->
        ConfirmDialog(
            title = stringResource(R.string.code_delete_file_title, path),
            body = null,
            onDismiss = { deleting = null },
            onConfirm = { controller.deleteFile(path) },
        )
    }
}

private val CODE_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

/**
 * A plain editor: one monospace text field without wrapping, line numbers beside it, and scrolling both ways.
 * The text on disk is reloaded when it changes, unless there are unsaved edits to lose.
 */
@Composable
private fun Editor(controller: CodeController, file: FileEntry, revision: Int, onClose: () -> Unit) {
    val path = file.path
    var saved by remember(path) { mutableStateOf<String?>(null) }
    var failure by remember(path) { mutableStateOf<Refusal?>(null) }
    var value by remember(path) { mutableStateOf(TextFieldValue()) }
    val tooLarge = file.size > MAX_EDITABLE_BYTES
    val dirty = saved != null && value.text != saved

    LaunchedEffect(path, revision) {
        if (tooLarge) return@LaunchedEffect
        val draft = controller.draft?.takeIf { it.path == path }
        if (saved == null && draft != null) {
            saved = draft.saved
            value = TextFieldValue(draft.text)
            return@LaunchedEffect
        }
        try {
            val text = controller.readText(path)
            failure = null
            // Unsaved edits win over a change on disk; saving them overwrites it.
            if (saved != null && value.text != saved && value.text != text) return@LaunchedEffect
            if (value.text != text) value = TextFieldValue(text, value.selection)
            saved = text
            controller.draft = null
        } catch (e: WorkspaceException) {
            failure = e.refusal
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (dirty) stringResource(R.string.code_unsaved, path) else path,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                enabled = dirty,
                onClick = { controller.writeText(path, value.text) },
            ) { Text(stringResource(R.string.code_save)) }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
        }
        val message = when {
            tooLarge -> stringResource(R.string.code_file_too_large)
            failure != null -> failure?.let { refusalText(it) }
            else -> null
        }
        if (message != null) {
            Text(message, modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (saved == null) return@Column

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
            val numbers = remember(lines) { (1..lines).joinToString("\n") }
            val paneHeight = maxHeight
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    numbers,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    style = CODE_STYLE,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.End,
                    softWrap = false,
                )
                // Scrolling sideways removes the width limit, so long lines stay on one line. The field
                // still fills the pane at least, so that a tap anywhere lands in it.
                BasicTextField(
                    value = value,
                    onValueChange = { new ->
                        value = new
                        val base = saved
                        controller.draft = if (base != null && new.text != base) Draft(path, base, new.text) else null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .defaultMinSize(minHeight = paneHeight)
                        .padding(top = 8.dp, bottom = 8.dp, end = 16.dp),
                    textStyle = CODE_STYLE.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
        }
    }
}
