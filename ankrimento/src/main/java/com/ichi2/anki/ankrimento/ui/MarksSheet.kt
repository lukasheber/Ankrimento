// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

class MarkBookmark(
    val id: String,
    val page: Int,
    val label: String,
    val comment: String,
)

/** A highlight; [page] is where it opens (a PDF page, an EPUB chapter), [place] how it is named (a page when empty). */
class MarkHighlight(
    val id: String,
    val page: Int,
    val text: String,
    val color: String,
    val note: String,
    val place: String = "",
    /** Upstream's annotation style: highlight, underline, squiggly, dashed, dotted or strikeout. */
    val style: String = "highlight",
)

/** Upstream's annotation styles, with their names. */
internal val HIGHLIGHT_STYLES =
    listOf(
        "highlight" to R.string.ankrimento_style_highlight,
        "underline" to R.string.ankrimento_style_underline,
        "squiggly" to R.string.ankrimento_style_squiggly,
        "dashed" to R.string.ankrimento_style_dashed,
        "dotted" to R.string.ankrimento_style_dotted,
        "strikeout" to R.string.ankrimento_style_strikeout,
    )

/** Upstream's highlight colours, in its order. */
internal val HIGHLIGHT_COLORS =
    linkedMapOf(
        "yellow" to Color(0xFFFFDC00),
        "green" to Color(0xFF00C850),
        "blue" to Color(0xFF1E90FF),
        "pink" to Color(0xFFFF508C),
        "aqua" to Color(0xFF2DD4BF),
        "orange" to Color(0xFFFB923C),
        "red" to Color(0xFFF87171),
        "purple" to Color(0xFFA855F7),
    )

/**
 * The document's bookmarks and highlights. A bookmark or a highlight opens its page;
 * the page shown can be bookmarked; a highlight's colour and note are changed, or it is
 * deleted, from its own dialog. Without [bookmarks], only the highlights are shown;
 * without [withHighlights], only the bookmarks. [placeOf] names a bookmark's place
 * and [addLabel] the action adding one (a page by default; a video's moment).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarksSheet(
    page: Int,
    bookmarks: List<MarkBookmark>?,
    highlights: List<MarkHighlight>,
    onOpenPage: (Int) -> Unit,
    onOpenHighlight: (MarkHighlight) -> Unit = { onOpenPage(it.page) },
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onHighlightColor: (String, String) -> Unit,
    onHighlightNote: (String, String) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onDismiss: () -> Unit,
    onHighlightStyle: (String, String) -> Unit = { _, _ -> },
    onBookmarkComment: (String, String) -> Unit = { _, _ -> },
    withHighlights: Boolean = true,
    placeOf: @Composable (MarkBookmark) -> String = { stringResource(R.string.ankrimento_page_short, it.page) },
    addLabel: String? = null,
    canAdd: Boolean = page > 0,
    openHighlight: String? = null,
) {
    // The viewer's own note button opens the sheet on that highlight's dialog.
    var tab by rememberSaveable { mutableIntStateOf(if (bookmarks == null || openHighlight != null) 1 else 0) }
    var chosen by remember { mutableStateOf(openHighlight) }
    var commenting by remember { mutableStateOf<MarkBookmark?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            if (bookmarks != null && !withHighlights) {
                Text(
                    stringResource(R.string.ankrimento_marks_bookmarks),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else if (bookmarks != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    listOf(R.string.ankrimento_marks_bookmarks, R.string.ankrimento_marks_highlights).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = tab == index,
                            onClick = { tab = index },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.ankrimento_marks_highlights),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (tab == 0 && bookmarks != null) {
                ListItem(
                    headlineContent = { Text(addLabel ?: stringResource(R.string.ankrimento_marks_add_bookmark, page)) },
                    leadingContent = { Icon(IncrementoIcons.Bookmark, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = canAdd, onClick = onAddBookmark),
                )
                if (bookmarks.isEmpty()) Empty(R.string.ankrimento_marks_no_bookmarks)
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        ListItem(
                            overlineContent = { Text(placeOf(bookmark)) },
                            headlineContent = {
                                Text(
                                    bookmark.comment.ifEmpty { bookmark.label },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { commenting = bookmark }) {
                                        Icon(IncrementoIcons.Edit, contentDescription = stringResource(R.string.ankrimento_marks_comment))
                                    }
                                    IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                                        Icon(
                                            IncrementoIcons.Delete,
                                            contentDescription = stringResource(R.string.ankrimento_marks_delete_bookmark),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onOpenPage(bookmark.page) },
                        )
                    }
                }
            } else {
                if (highlights.isEmpty()) Empty(R.string.ankrimento_marks_no_highlights)
                LazyColumn(Modifier.heightIn(max = 560.dp)) {
                    items(highlights, key = { it.id }) { highlight ->
                        ListItem(
                            overlineContent = {
                                Text(
                                    highlight.place.ifEmpty { stringResource(R.string.ankrimento_page_short, highlight.page) },
                                )
                            },
                            headlineContent = { Text(highlight.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            supportingContent =
                                highlight.note.takeIf { it.isNotEmpty() }?.let { note ->
                                    { Text(note, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                },
                            leadingContent = { Swatch(HIGHLIGHT_COLORS[highlight.color] ?: Color.Gray, selected = false, size = 16) },
                            trailingContent = {
                                IconButton(onClick = { chosen = highlight.id }) {
                                    Icon(
                                        IncrementoIcons.More,
                                        contentDescription = stringResource(R.string.ankrimento_marks_edit_highlight),
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onOpenHighlight(highlight) },
                        )
                    }
                }
            }
        }
    }
    highlights.firstOrNull { it.id == chosen }?.let { highlight ->
        HighlightDialog(
            highlight,
            onColor = { onHighlightColor(highlight.id, it) },
            onStyle = { onHighlightStyle(highlight.id, it) },
            onNote = { onHighlightNote(highlight.id, it) },
            onDelete = {
                chosen = null
                onDeleteHighlight(highlight.id)
            },
            onDismiss = { chosen = null },
        )
    }
    commenting?.let { bookmark ->
        var comment by remember(bookmark.id) { mutableStateOf(bookmark.comment) }
        AlertDialog(
            onDismissRequest = { commenting = null },
            title = { Text(placeOf(bookmark)) },
            text = {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.ankrimento_marks_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    commenting = null
                    if (comment != bookmark.comment) onBookmarkComment(bookmark.id, comment)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { commenting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun Empty(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    size: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size.dp)
            .background(color, CircleShape)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
    )
}

/**
 * The PDF viewer's colour button: a highlight's colour, the colour for the next highlight, or
 * ([forDefault]) the default one. [onChoose] gets the colour's name, or "" when closed without one.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun HighlightColorDialog(
    current: String,
    forDefault: Boolean,
    onChoose: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onChoose("") },
        title = {
            Text(stringResource(if (forDefault) R.string.ankrimento_default_highlight_colour else R.string.ankrimento_highlight_colour))
        },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HIGHLIGHT_COLORS.forEach { (name, color) ->
                    Swatch(
                        color,
                        selected = current == name,
                        size = SWATCH_SIZE,
                        modifier = Modifier.clickable { onChoose(name) }.semantics { contentDescription = name },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose("") }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** A colour to tap: Android's 48dp touch target. */
private const val SWATCH_SIZE = 48

/** A highlight's colour, note and deletion, as desktop's highlight menu offers them. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HighlightDialog(
    highlight: MarkHighlight,
    onColor: (String) -> Unit,
    onStyle: (String) -> Unit,
    onNote: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(highlight.place.ifEmpty { stringResource(R.string.ankrimento_page_short, highlight.page) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(highlight.text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HIGHLIGHT_COLORS.forEach { (name, color) ->
                        Swatch(
                            color,
                            selected = highlight.color == name,
                            size = SWATCH_SIZE,
                            modifier = Modifier.clickable { onColor(name) }.semantics { contentDescription = name },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HIGHLIGHT_STYLES.forEach { (name, label) ->
                        FilterChip(selected = highlight.style == name, onClick = { onStyle(name) }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.ankrimento_marks_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (note != highlight.note) onNote(note)
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.ankrimento_marks_delete_highlight)) } },
    )
}
