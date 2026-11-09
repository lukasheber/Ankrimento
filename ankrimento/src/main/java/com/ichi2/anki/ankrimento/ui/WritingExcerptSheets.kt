// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/**
 * Where a selected passage goes: one of the writing topics, or a new one named here. Desktop sends it to the writing
 * dock that is open; on a phone nothing is open beside the reader, so the choice is made on the spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WritingPickerSheet(
    topics: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(topics.isEmpty()) }
    var title by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.ankrimento_send_to_writing),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (naming) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(MAX_TITLE) },
                        label = { Text(stringResource(R.string.ankrimento_writing_title_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = title.isNotBlank(), onClick = { onNew(title.trim()) }) {
                            Text(stringResource(R.string.ankrimento_writing_create_and_send))
                        }
                    }
                }
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ankrimento_writing_new_topic)) },
                    leadingContent = { Icon(IncrementoIcons.Add, contentDescription = null) },
                    modifier = Modifier.clickable { naming = true },
                )
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(topics) { (id, name) ->
                    ListItem(
                        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(IncrementoIcons.TextFormat, contentDescription = null) },
                        modifier = Modifier.clickable { onPick(id) },
                    )
                }
            }
        }
    }
}

/** One passage in a list: the words quoted, and where it is from or where it went. */
internal class ExcerptRow(
    val quote: String,
    val where: String,
    val onOpen: () -> Unit,
)

/**
 * The passages that link a document and a writing topic, seen from either end: tap one to go to the other end.
 * Desktop marks them in the reader's margin and the writing's; a phone has no margin to spare, so they are listed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExcerptListSheet(
    title: String,
    rows: List<ExcerptRow>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.ankrimento_writing_excerpts_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows) { row ->
                    ListItem(
                        headlineContent = { Text(row.quote, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(row.where, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier =
                            Modifier.clickable {
                                onDismiss()
                                row.onOpen()
                            },
                    )
                }
            }
        }
    }
}

private const val MAX_TITLE = 120
