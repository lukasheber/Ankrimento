// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import kotlinx.coroutines.delay

/**
 * Desktop's Batch Q/A: pairs pasted or selected (alternating lines, or Q:/A:
 * blocks), previewed as desktop parses them, then one card each. [labels]
 * are desktop's names for the sheet (see `extract.batch_labels`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchSheet(
    labels: Map<String, String>,
    initialText: String,
    preview: suspend (String) -> List<Ankrimento.BatchRow>,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var rows by remember { mutableStateOf(emptyList<Ankrimento.BatchRow>()) }
    LaunchedEffect(text) {
        // Parsed once typing pauses.
        delay(PREVIEW_DELAY_MS)
        rows = runCatching { preview(text) }.getOrDefault(emptyList())
    }
    val valid = rows.count { it.valid }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(labels["title"].orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(labels["hint"].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                itemsIndexed(rows) { index, row ->
                    ListItem(
                        overlineContent = { Text("${index + 1}") },
                        headlineContent = { Text(row.question.ifEmpty { "–" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            if (row.valid) {
                                Text(row.answer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            } else {
                                Text("${labels["invalid"].orEmpty()}: ${row.error}", color = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
            }
            Button(onClick = { onCreate(text) }, enabled = valid > 0, modifier = Modifier.fillMaxWidth()) {
                Text("${labels["create"].orEmpty()} ($valid)")
            }
        }
    }
}

private const val PREVIEW_DELAY_MS = 300L
