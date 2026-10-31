// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R
import kotlin.math.roundToInt

/** A reader action: an icon in the top bar when it has one, else an overflow entry. */
class ReaderTool(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * What a reader shows in a session: Incremento's answer buttons for the
 * card, and the topic actions desktop puts in the reviewer's menu.
 */
internal class ReviewBarState(
    val buttons: List<Ankrimento.AnswerButton>,
    val topicActions: List<ReaderTool>,
    /** Returns to the previous element, as SuperMemo's Back; null when there is none to return to. */
    val onBack: (() -> Unit)?,
    val onAnswer: (Rating) -> Unit,
)

/** A paged document's position: [page] of [pages], 1-based; [onPage] goes to another page. */
class ReaderPager(
    val page: Int,
    val pages: Int,
    val onPage: (Int) -> Unit,
)

/** Where the reader is in the document: [fraction] of it read, [label] for where ("Chapter 3 of 12"). */
class ReaderProgress(
    val fraction: Float,
    val label: String,
)

/** Icons shown in the top bar before the rest move to the overflow menu. */
private const val MAX_BAR_ICONS = 3

/**
 * A reader's screen: the document fills it; a top bar names it and holds
 * the reader's tools; in a session, the review bar is at the bottom, within
 * thumb reach, so reading ends with a single tap on how soon to return.
 *
 * With [chromeVisible] false only the document shows, as in an ebook app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScaffold(
    title: String,
    subtitle: String?,
    tools: List<ReaderTool>,
    menu: List<ReaderTool>,
    review: ReviewBarState?,
    onBack: () -> Unit,
    chromeVisible: Boolean = true,
    priority: Double? = null,
    onPriority: () -> Unit = {},
    progress: ReaderProgress? = null,
    pager: ReaderPager? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            AnimatedVisibility(chromeVisible, enter = expandVertically(), exit = shrinkVertically()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (!subtitle.isNullOrEmpty()) {
                                Text(subtitle, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                    actions = {
                        if (priority != null) PriorityChip(priority, onPriority)
                        val barIcons = tools.filter { it.icon != null }.take(MAX_BAR_ICONS)
                        barIcons.forEach { tool ->
                            IconButton(onClick = tool.onClick) { Icon(tool.icon!!, contentDescription = tool.label) }
                        }
                        OverflowMenu(tools.filterNot { it in barIcons } + menu)
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(chromeVisible, enter = expandVertically(expandFrom = Alignment.Top), exit = shrinkVertically()) {
                Column {
                    if (progress != null) ProgressStrip(progress, insetBottom = review == null)
                    if (pager != null) PagerStrip(pager, insetBottom = review == null)
                    if (review != null) ReviewBar(review)
                }
            }
        },
    ) { padding: PaddingValues ->
        content(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        )
    }
}

/**
 * Page turning for a paged document: the arrows step a page, the slider
 * scrubs through the whole document and goes there when let go.
 */
@Composable
private fun PagerStrip(
    pager: ReaderPager,
    insetBottom: Boolean,
) {
    var dragged by remember(pager.page, pager.pages) { mutableStateOf<Float?>(null) }
    val shown = dragged?.roundToInt() ?: pager.page
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = (if (insetBottom) Modifier.navigationBarsPadding() else Modifier).fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pager.onPage(pager.page - 1) }, enabled = pager.page > 1) {
                Icon(IncrementoIcons.Previous, contentDescription = stringResource(R.string.ankrimento_pdf_previous))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.ankrimento_page_of, shown, pager.pages),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (pager.pages > 1) {
                    Slider(
                        value = dragged ?: pager.page.toFloat(),
                        onValueChange = { dragged = it },
                        onValueChangeFinished = {
                            dragged?.roundToInt()?.let { if (it != pager.page) pager.onPage(it) }
                            dragged = null
                        },
                        valueRange = 1f..pager.pages.toFloat(),
                        modifier = Modifier.height(32.dp),
                    )
                }
            }
            IconButton(onClick = { pager.onPage(pager.page + 1) }, enabled = pager.page < pager.pages) {
                Icon(IncrementoIcons.Next, contentDescription = stringResource(R.string.ankrimento_pdf_next))
            }
        }
    }
}

/** A thin bar of how much is read, with where the reader is and the percentage. */
@Composable
private fun ProgressStrip(
    progress: ReaderProgress,
    insetBottom: Boolean,
) {
    Surface(tonalElevation = 3.dp) {
        Column(if (insetBottom) Modifier.navigationBarsPadding() else Modifier) {
            LinearProgressIndicator(
                progress = { progress.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    progress.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ankrimento_book_progress, (progress.fraction.coerceIn(0f, 1f) * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(entries: List<ReaderTool>) {
    if (entries.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(IncrementoIcons.More, contentDescription = stringResource(R.string.ankrimento_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = entry.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                    onClick = {
                        expanded = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Incremento's answer buttons, left to right as on the reviewer, each with
 * when the card returns. The middle choice is the filled one: it is the
 * usual answer, and the eye finds it first.
 */
@Composable
private fun ReviewBar(state: ReviewBarState) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.onBack?.invoke() }, enabled = state.onBack != null) {
                Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_previous_element))
            }
            val buttons = state.buttons.sortedBy { it.rating.number }
            buttons.forEachIndexed { index, button ->
                val emphasized = buttons.size == 3 && index == 1
                val modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 4.dp)
                val label: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(button.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!button.due.isNullOrEmpty()) {
                            Text(button.due, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                val padding = ButtonDefaults.ContentPadding
                if (emphasized) {
                    Button(onClick = { state.onAnswer(button.rating) }, modifier = modifier, contentPadding = padding) { label() }
                } else {
                    FilledTonalButton(
                        onClick = { state.onAnswer(button.rating) },
                        modifier = modifier,
                        contentPadding = padding,
                    ) { label() }
                }
            }
            if (state.topicActions.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                OverflowMenu(state.topicActions)
            }
        }
    }
}

/**
 * The element's Incremento priority, always in view: SuperMemo's most used
 * control is one tap away rather than in a menu.
 */
@Composable
private fun PriorityChip(
    priority: Double,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(stringResource(R.string.ankrimento_priority_chip, priority.roundToInt())) },
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** Shown while a reader opens its document. */
@Composable
internal fun ReaderLoading(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * Incremento priority, 0 to 100, chosen with the thumb; the number stays in
 * view as it changes.
 */
@Composable
internal fun PriorityDialog(
    initial: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var value by remember { mutableFloatStateOf(initial.toFloat().coerceIn(0f, 100f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ankrimento_priority_title)) },
        text = {
            Column {
                Text(
                    value.roundToInt().toString(),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f)
                HorizontalDivider()
                Text(
                    stringResource(R.string.ankrimento_priority_message),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.roundToInt().toDouble()) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
