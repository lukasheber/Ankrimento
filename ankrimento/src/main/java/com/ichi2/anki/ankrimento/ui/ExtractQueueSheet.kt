// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R
import kotlin.math.roundToInt

/** In the top bar while extracts wait to be added: how many; a tap opens them. */
@Composable
internal fun ExtractQueueChip(
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.ankrimento_extract_queued, count, count),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** The extracts set aside with Add later: each can be removed; Add all adds them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtractQueueSheet(
    queued: List<Ankrimento.QueuedExtract>,
    onRemove: (Int) -> Unit,
    onAddAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.ankrimento_extract_queue_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.ankrimento_extract_queue_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(queued) { index, extract ->
                    ListItem(
                        headlineContent = { Text(extract.front, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (extract.isTopic) R.string.ankrimento_extract_as_topic else R.string.ankrimento_extract_item,
                                ) +
                                    " · " + stringResource(R.string.ankrimento_priority_chip, extract.priority.roundToInt()),
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(index) }) {
                                Icon(IncrementoIcons.Delete, contentDescription = stringResource(R.string.ankrimento_extract_queue_remove))
                            }
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onAddAll,
                    enabled = queued.isNotEmpty(),
                ) { Text(stringResource(R.string.ankrimento_extract_queue_add_all)) }
            }
        }
    }
}
