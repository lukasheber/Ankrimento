// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/** One thing the sheet does: a [label] and what it runs. */
internal class ReadingMark(
    val label: String,
    val onClick: () -> Unit,
)

/** Pages of a PDF a range can be marked in: the page in view, and how many there are. */
internal class PageRange(
    val current: Int,
    val pages: Int,
    val onRange: (from: Int, to: Int, read: Boolean) -> Unit,
)

/**
 * Desktop spreads reading marks over a context menu (this page, a range of pages, a passage, a section). On a phone
 * they share one sheet, opened from the reader's menu: [figures] says where the document stands (desktop's
 * "Viewed 40% · Marked read 10%"), each of [marks] is one tap, and [range] adds a from/to pair for pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingMarksSheet(
    figures: String?,
    marks: List<ReadingMark>,
    range: PageRange? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.ankrimento_reading_marks),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (!figures.isNullOrEmpty()) {
                Text(
                    figures,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }
            for (mark in marks) {
                ListItem(
                    headlineContent = { Text(mark.label) },
                    modifier =
                        Modifier.clickable {
                            onDismiss()
                            mark.onClick()
                        },
                )
            }
            if (range != null) PageRangeRow(range, onDismiss)
        }
    }
}

/** From and to page, then Mark read or Unmark: desktop's "Mark pages read" without typing "3-7". */
@Composable
private fun PageRangeRow(
    range: PageRange,
    onDismiss: () -> Unit,
) {
    var from by remember { mutableStateOf(range.current.toString()) }
    var to by remember { mutableStateOf(range.current.toString()) }
    val start = from.toIntOrNull()
    val end = to.toIntOrNull()
    val valid = start != null && end != null && start in 1..end && end <= range.pages
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.ankrimento_mark_pages, range.pages), style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = from,
                onValueChange = { from = it.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.ankrimento_mark_pages_from)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = to,
                onValueChange = { to = it.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.ankrimento_mark_pages_to)) },
                singleLine = true,
                isError = end != null && end > range.pages,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                enabled = valid,
                onClick = {
                    onDismiss()
                    range.onRange(start ?: return@TextButton, end ?: return@TextButton, false)
                },
            ) { Text(stringResource(R.string.ankrimento_mark_pages_unmark)) }
            TextButton(
                enabled = valid,
                onClick = {
                    onDismiss()
                    range.onRange(start ?: return@TextButton, end ?: return@TextButton, true)
                },
            ) { Text(stringResource(R.string.ankrimento_mark_pages_read)) }
        }
    }
}
