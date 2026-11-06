// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import android.text.Html
import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R
import kotlin.math.roundToInt

/** What the extract sheet adds: fields as HTML, the note type, topic or item, and the priority. */
internal class ExtractChoice(
    val fields: List<String>,
    val noteType: String,
    val isTopic: Boolean,
    val priority: Double,
    val deck: String = "",
)

/**
 * A new card from an extract, the mobile counterpart of desktop's Add Card
 * dock: Item or Topic first (what the card is for decides the rest), the
 * fields, the priority within thumb reach, then Add. Add later sets it aside
 * as chosen, so the next selection starts afresh; the waiting extracts are
 * added together. Keep extracting closes
 * the sheet and keeps the draft, so more can be selected into it; Discard
 * drops it. Formatting from the document is kept unless the text is edited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtractSheet(
    draft: Ankrimento.ExtractDraft,
    onAdd: (ExtractChoice) -> Unit,
    onLater: ((ExtractChoice) -> Unit)?,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    batchLabel: String? = null,
    onBatch: (ExtractChoice, String) -> Unit = { _, _ -> },
    mediaDir: java.io.File? = null,
) {
    // An image (a PDF snapshot) shows above its field; its stand-in character stays out of the text.
    val originals =
        remember(
            draft,
        ) {
            draft.fields.map {
                Html
                    .fromHtml(it, Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .replace(OBJECT_CHARACTER, "")
                    .trim()
            }
        }
    val images =
        remember(draft) {
            draft.fields.map { html ->
                IMAGE_SOURCE.findAll(html).map { Html.fromHtml(it.groupValues[1], 0).toString() }.toList()
            }
        }
    var texts by remember(draft) { mutableStateOf(originals) }
    var isTopic by remember(draft) { mutableStateOf(draft.isTopic) }
    var noteType by remember(draft) { mutableStateOf(draft.noteType) }
    var priority by remember(draft) { mutableFloatStateOf(draft.priority.toFloat().coerceIn(0f, 100f)) }
    var deck by remember(draft) { mutableStateOf(draft.deck) }
    val fromScratch = draft.decks.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onKeep, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding()
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (fromScratch) R.string.ankrimento_new_card_title else R.string.ankrimento_extract_title),
                style = MaterialTheme.typography.titleLarge,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to R.string.ankrimento_extract_item, true to R.string.ankrimento_extract_as_topic).forEachIndexed {
                    index,
                    (topic, label),
                    ->
                    SegmentedButton(
                        selected = isTopic == topic,
                        onClick = { isTopic = topic },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(label)) }
                }
            }

            texts.forEachIndexed { index, text ->
                if (mediaDir != null) images.getOrNull(index)?.forEach { name -> FieldImage(java.io.File(mediaDir, name)) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { changed -> texts = texts.toMutableList().also { it[index] = changed } },
                    label = {
                        // The chosen note type's own field names ("Text" for a cloze), Front/Back otherwise.
                        val fallback = if (index == 0) R.string.ankrimento_extract_front else R.string.ankrimento_extract_back
                        Text(draft.fieldNames[noteType]?.getOrNull(index) ?: stringResource(fallback))
                    },
                    minLines = if (index == 0) 3 else 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            NoteTypeChooser(draft.noteTypes, noteType) { noteType = it }
            if (fromScratch) Chooser(R.string.ankrimento_new_card_deck, draft.decks, deck) { deck = it }

            Column {
                Text(
                    stringResource(R.string.ankrimento_extract_priority_value, priority.roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(value = priority, onValueChange = { priority = it }, valueRange = 0f..100f)
            }

            fun choice(): ExtractChoice {
                val fields = draft.fields.mapIndexed { index, html -> fieldHtml(html, originals[index], texts[index]) }
                return ExtractChoice(fields, noteType, isTopic, priority.roundToInt().toDouble(), deck)
            }
            val ready = texts.firstOrNull()?.isNotBlank() == true || images.firstOrNull()?.isNotEmpty() == true
            // The main action first; the other two say what they do, so nothing seems lost.
            Button(onClick = { onAdd(choice()) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ankrimento_extract_add))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onLater?.let { later ->
                    OutlinedButton(onClick = { later(choice()) }, enabled = ready, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ankrimento_extract_later))
                    }
                }
                if (!fromScratch) {
                    OutlinedButton(
                        onClick = onKeep,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.ankrimento_extract_keep)) }
                }
            }
            if (!fromScratch) {
                Text(
                    stringResource(
                        if (onLater !=
                            null
                        ) {
                            R.string.ankrimento_extract_actions_help
                        } else {
                            R.string.ankrimento_extract_keep_help
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.ankrimento_extract_discard)) }
                // Desktop's Batch Q/A: the text as pairs, one card each.
                batchLabel?.let { label -> TextButton(onClick = { onBatch(choice(), texts.firstOrNull().orEmpty()) }) { Text(label) } }
            }
        }
    }
}

/**
 * A field's HTML after editing: unchanged text keeps the document's formatting;
 * text typed after it keeps that formatting too (a video extract's link back
 * to its moment, say) and adds the rest as plain text; other edits become plain text.
 */
internal fun fieldHtml(
    originalHtml: String,
    originalText: String,
    edited: String,
): String {
    val text = edited.trim()

    fun plain(value: String) = TextUtils.htmlEncode(value).replace("\n", "<br>")
    return when {
        text == originalText -> originalHtml
        originalText.isNotEmpty() && text.startsWith(originalText) -> originalHtml + plain(text.removePrefix(originalText))
        else -> plain(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteTypeChooser(
    noteTypes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) = Chooser(R.string.ankrimento_extract_note_type, noteTypes, selected, onSelect)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chooser(
    label: Int,
    noteTypes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            noteTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    onClick = {
                        expanded = false
                        onSelect(type)
                    },
                )
            }
        }
    }
}

private const val OBJECT_CHARACTER = "\uFFFC"
private val IMAGE_SOURCE = Regex("""<img[^>]*\ssrc="([^"]+)"""")

/** A field's image from the collection's media, scaled down to the sheet. */
@Composable
private fun FieldImage(file: java.io.File) {
    val bitmap =
        remember(file) {
            runCatching {
                val bounds =
                    android.graphics.BitmapFactory
                        .Options()
                        .apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > MAX_IMAGE_PX || bounds.outHeight / sample > MAX_IMAGE_PX) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    file.path,
                    android.graphics.BitmapFactory
                        .Options()
                        .apply { inSampleSize = sample },
                )
            }.getOrNull()
        } ?: return
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.heightIn(max = 160.dp),
    )
}

private const val MAX_IMAGE_PX = 1024
