// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ichi2.anki.ankrimento.R
import com.ichi2.anki.ankrimento.ReadingPreferences
import kotlin.math.roundToInt

/** A book's page colours, as upstream's EPUB appearance modes paint them. */
private class PageStyle(
    val mode: String,
    val label: Int,
    val background: Color,
    val text: Color,
)

private val PAGE_STYLES =
    listOf(
        PageStyle("original", R.string.ankrimento_page_original, Color.White, Color(0xFF202124)),
        PageStyle("dark", R.string.ankrimento_page_dark, Color(0xFF1E1E1E), Color(0xFFDEDEDE)),
        PageStyle("night", R.string.ankrimento_page_night, Color(0xFF15120F), Color(0xFFD9C7A4)),
    )

/**
 * Text and page settings, in the order a reader reaches for them: size
 * first, then the page colour, then the finer typography.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingSettingsSheet(
    textScale: Double,
    appearance: String,
    preferences: ReadingPreferences,
    onTextScale: (Double) -> Unit,
    onAppearance: (String) -> Unit,
    onPreferences: (ReadingPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onTextScale(textScale - TEXT_SCALE_STEP) }, modifier = Modifier.size(56.dp)) {
                    Text("A", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(textScale * 100).roundToInt()}%", style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.ankrimento_text_size), style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalIconButton(onClick = { onTextScale(textScale + TEXT_SCALE_STEP) }, modifier = Modifier.size(56.dp)) {
                    Text("A", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                }
            }

            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                PAGE_STYLES.forEach { style ->
                    PageSwatch(style, selected = style.mode == appearance) { onAppearance(style.mode) }
                }
            }

            Choice(
                label = R.string.ankrimento_typeface,
                options = ReadingPreferences.Typeface.entries,
                selected = preferences.typeface,
                optionLabel = {
                    when (it) {
                        ReadingPreferences.Typeface.ORIGINAL -> R.string.ankrimento_typeface_original
                        ReadingPreferences.Typeface.SERIF -> R.string.ankrimento_typeface_serif
                        ReadingPreferences.Typeface.SANS -> R.string.ankrimento_typeface_sans
                    }
                },
            ) { onPreferences(preferences.copy(typeface = it)) }
            Choice(
                label = R.string.ankrimento_line_spacing,
                options = ReadingPreferences.Spacing.entries,
                selected = preferences.spacing,
                optionLabel = {
                    when (it) {
                        ReadingPreferences.Spacing.COMPACT -> R.string.ankrimento_spacing_compact
                        ReadingPreferences.Spacing.NORMAL -> R.string.ankrimento_spacing_normal
                        ReadingPreferences.Spacing.RELAXED -> R.string.ankrimento_spacing_relaxed
                    }
                },
            ) { onPreferences(preferences.copy(spacing = it)) }
            Choice(
                label = R.string.ankrimento_margins,
                options = ReadingPreferences.Margins.entries,
                selected = preferences.margins,
                optionLabel = {
                    when (it) {
                        ReadingPreferences.Margins.NARROW -> R.string.ankrimento_margins_narrow
                        ReadingPreferences.Margins.NORMAL -> R.string.ankrimento_margins_normal
                        ReadingPreferences.Margins.WIDE -> R.string.ankrimento_margins_wide
                    }
                },
            ) { onPreferences(preferences.copy(margins = it)) }
        }
    }
}

@Composable
private fun PageSwatch(
    style: PageStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        val ring =
            if (selected) {
                BorderStroke(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                )
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            }
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .border(ring, CircleShape)
                    .padding(4.dp)
                    .background(style.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = style.text, fontFamily = FontFamily.Serif, fontSize = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(style.label), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: Int,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(optionLabel(option)), maxLines = 1) }
            }
        }
    }
}

/** The book's chapters; the one being read is marked and in view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(
    chapters: List<String>,
    current: Int,
    onChapter: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.ankrimento_contents),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
        LazyColumn(state = list, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(chapters) { index, title ->
                val reading = index == current
                ListItem(
                    headlineContent = {
                        Text(
                            title.ifBlank { stringResource(R.string.ankrimento_chapter_number, index + 1) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (reading) FontWeight.SemiBold else null,
                        )
                    },
                    leadingContent = {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (reading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp),
                        )
                    },
                    supportingContent = if (reading) ({ Text(stringResource(R.string.ankrimento_reading_now)) }) else null,
                    colors =
                        if (reading) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ListItemDefaults.colors()
                        },
                    modifier = Modifier.clickable { onChapter(index) },
                )
            }
        }
    }
}

/** Desktop's text size buttons step by a tenth. */
private const val TEXT_SCALE_STEP = 0.1
