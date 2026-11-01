// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R

/**
 * Desktop's Review All for a document: which of its cards (all or due, topics or items, the
 * whole document or up to here) and in what order, with how many that takes; Review starts it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DocumentReviewSheet(
    preview: Ankrimento.DocumentReviewPreview,
    choices: Map<String, String>,
    onChoose: (String, String) -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ankrimento_document_review), style = MaterialTheme.typography.titleLarge)
            for (group in GROUPS) {
                val options = preview.labels[group] ?: continue
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (value, label) ->
                        FilterChip(
                            selected = (choices[group] ?: options.first().first) == value,
                            onClick = { onChoose(group, value) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Text(
                if (preview.selected == 0) {
                    stringResource(R.string.ankrimento_document_review_none)
                } else {
                    pluralStringResource(
                        R.plurals.ankrimento_document_review_count,
                        preview.selected,
                        preview.selected,
                        preview.topics,
                        preview.items,
                    )
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onReview, enabled = preview.selected > 0, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ankrimento_document_review_start))
            }
        }
    }
}

/** Desktop's choices, in its dialog's order. */
private val GROUPS = listOf("state", "card_kind", "media_range", "order")
