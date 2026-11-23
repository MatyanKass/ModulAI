package com.modulai.app.ui.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modulai.app.R
import com.modulai.app.code.CodeController
import com.modulai.app.code.CodeState
import com.modulai.core.code.ChangeKind
import com.modulai.core.code.DiffKind
import com.modulai.core.code.FileChange
import com.modulai.core.code.Hunk
import com.modulai.core.code.diffLines
import com.modulai.core.code.stats

/** A diff longer than this is cut on screen; the file itself is one tap away in the Files tab. */
private const val MAX_DIFF_LINES = 400

private val DIFF_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
internal fun ChangesPane(controller: CodeController, state: CodeState) {
    if (state.changes.isEmpty()) {
        Text(
            stringResource(R.string.code_changes_empty),
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.changes, key = { it.path }) { change ->
            ChangeCard(change, canRevert = !state.running, onRevert = { controller.revert(change.path) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ChangeCard(change: FileChange, canRevert: Boolean, onRevert: () -> Unit) {
    var expanded by rememberSaveable(change.path) { mutableStateOf(true) }
    val hunks = remember(change) { if (change.binary) emptyList() else diffLines(change.before, change.after) }
    val added = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val removed = MaterialTheme.colorScheme.error

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(change.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val stats = hunks.stats()
                Text(
                    when {
                        change.kind == ChangeKind.DELETED -> stringResource(R.string.code_change_deleted)
                        change.binary -> stringResource(R.string.code_change_binary)
                        change.kind == ChangeKind.CREATED -> stringResource(R.string.code_change_created, stats.added)
                        else -> stringResource(R.string.code_change_modified, stats.added, stats.removed)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevert, enabled = canRevert) { Text(stringResource(R.string.code_revert)) }
        }
        if (expanded && hunks.isNotEmpty()) {
            val text = remember(hunks, added, removed) { render(hunks, added, removed) }
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    style = DIFF_STYLE,
                    softWrap = false,
                )
            }
            if (hunks.sumOf { it.lines.size } > MAX_DIFF_LINES) {
                Text(
                    stringResource(R.string.code_diff_cut),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The hunks as one text in unified-diff form: far cheaper to lay out than a row per line. */
private fun render(hunks: List<Hunk>, added: Color, removed: Color): AnnotatedString = buildAnnotatedString {
    var left = MAX_DIFF_LINES
    for (hunk in hunks) {
        if (left <= 0) break
        if (length > 0) append('\n')
        withStyle(SpanStyle(color = Color.Gray)) {
            append("@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@")
        }
        for (line in hunk.lines.take(left)) {
            append('\n')
            when (line.kind) {
                DiffKind.SAME -> append("  ${line.text}")
                DiffKind.ADDED -> withStyle(SpanStyle(color = added)) { append("+ ${line.text}") }
                DiffKind.REMOVED -> withStyle(SpanStyle(color = removed)) { append("- ${line.text}") }
            }
        }
        left -= hunk.lines.size
    }
}
