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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R

/**
 * Desktop's "cards from highlights" dialog: the PDF's highlights without a card, all chosen at first,
 * one card each in the chosen note type, as items or topics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightCardsSheet(
    plan: Ankrimento.HighlightCardsPlan,
    onCreate: (ids: List<String>, noteType: String, isTopic: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(plan.rows.map { it.id }.toSet()) }
    var noteType by remember { mutableStateOf(plan.noteType) }
    var isTopic by remember { mutableStateOf(plan.isTopic) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ankrimento_highlight_cards_title), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(plan.rows, key = { it.id }) { row ->
                    val selected = row.id in chosen
                    ListItem(
                        headlineContent = { Text(row.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.ankrimento_page_short, row.page)) },
                        leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                        modifier =
                            Modifier.clickable(role = Role.Checkbox) {
                                chosen = if (selected) chosen - row.id else chosen + row.id
                            },
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to R.string.ankrimento_extract_item, true to R.string.ankrimento_extract_as_topic)
                    .forEachIndexed { index, (topic, label) ->
                        SegmentedButton(
                            selected = isTopic == topic,
                            onClick = { isTopic = topic },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
            }
            NoteTypeChooser(plan.noteTypes, noteType) { noteType = it }
            Button(
                onClick = { onCreate(plan.rows.map { it.id }.filter { it in chosen }, noteType, isTopic) },
                enabled = chosen.isNotEmpty() && noteType.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(pluralStringResource(R.plurals.ankrimento_highlight_cards_create, chosen.size, chosen.size)) }
        }
    }
}

/** Desktop's reading limit dialog for one PDF, in desktop's words: on or off, pages a day, what happens past it. */
@Composable
internal fun PageLimitDialog(
    limit: Ankrimento.PageLimit,
    onSave: (enabled: Boolean, pages: Int, mode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(limit.enabled) }
    var pages by remember { mutableStateOf(limit.pages.toString()) }
    var mode by remember { mutableStateOf(limit.mode) }
    val count = pages.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(limit.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Switch) { enabled = !enabled }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(limit.enabledLabel, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = null)
                }
                OutlinedTextField(
                    value = pages,
                    onValueChange = { typed -> pages = typed.filter(Char::isDigit).take(4) },
                    label = { Text(limit.limitLabel.trimEnd(':')) },
                    enabled = enabled,
                    singleLine = true,
                    isError = enabled && (count == null || count !in 1..MAX_PAGES),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                limit.modes.forEach { (key, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = mode == key, enabled = enabled, role = Role.RadioButton) { mode = key },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == key, onClick = null, enabled = enabled)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Text(limit.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(enabled, (count ?: 1).coerceIn(1, MAX_PAGES), mode) },
                enabled = !enabled || (count != null && count in 1..MAX_PAGES),
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Desktop's spin box range. */
private const val MAX_PAGES = 5000
