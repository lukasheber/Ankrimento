// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/** A page that matches a search, with a short excerpt around the match. */
class FindHit(
    val page: Int,
    val snippet: String,
)

/**
 * Find in the document: a search field, then the matching pages with their excerpts.
 * Choosing one opens it, with the matches highlighted on the page.
 *
 * @param status a line under the field instead of the results (searching, reading the text, no matches)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindSheet(
    query: String,
    hits: List<FindHit>,
    status: String?,
    onSearch: (String) -> Unit,
    onHit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(query) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.ankrimento_find_hint)) },
                leadingIcon = { Icon(IncrementoIcons.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .focusRequester(focus),
            )
            LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
            val line = status ?: if (query.isNotEmpty()) pluralStringResource(hits.size) else null
            line?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(hits) { index, hit ->
                    ListItem(
                        overlineContent = { Text(stringResource(R.string.ankrimento_page_short, hit.page)) },
                        headlineContent = {
                            Text(highlighted(hit.snippet, query), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable { onHit(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun pluralStringResource(count: Int): String =
    androidx.compose.ui.res
        .pluralStringResource(R.plurals.ankrimento_find_pages, count, count)

/** The excerpt with each occurrence of the query's words in bold. */
private fun highlighted(
    snippet: String,
    query: String,
): AnnotatedString {
    val words = query.split(Regex("\\s+")).filter { it.length >= 2 }.map { it.lowercase() }
    if (words.isEmpty()) return AnnotatedString(snippet)
    val lower = snippet.lowercase()
    val marks = BooleanArray(snippet.length)
    for (word in words) {
        var at = lower.indexOf(word)
        while (at >= 0) {
            for (i in at until at + word.length) marks[i] = true
            at = lower.indexOf(word, at + word.length)
        }
    }
    return buildAnnotatedString {
        snippet.forEachIndexed { i, char ->
            if (marks[i]) withBold { append(char) } else append(char)
        }
    }
}

private inline fun AnnotatedString.Builder.withBold(block: AnnotatedString.Builder.() -> Unit) {
    val start = pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
    block()
    pop(start)
}

/** Over the page after a match is opened: previous and next match, all matches, and close. */
@Composable
internal fun FindBar(
    position: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShowAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onPrevious, enabled = position > 0) {
                Icon(IncrementoIcons.Previous, contentDescription = stringResource(R.string.ankrimento_find_previous))
            }
            Text(
                stringResource(R.string.ankrimento_find_position, position + 1, total),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onShowAll).padding(horizontal = 8.dp, vertical = 12.dp),
            )
            IconButton(onClick = onNext, enabled = position < total - 1) {
                Icon(IncrementoIcons.Next, contentDescription = stringResource(R.string.ankrimento_find_next))
            }
            IconButton(onClick = onClose) {
                Icon(IncrementoIcons.Close, contentDescription = stringResource(R.string.ankrimento_find_close))
            }
        }
    }
}
