// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
    /** What it does, in a line, where a menu has room for it. */
    val description: String? = null,
    val onClick: () -> Unit,
)

/**
 * What a reader shows in a session: Incremento's answer buttons for the
 * card, and the topic actions desktop puts in the reviewer's menu.
 */
internal class ReviewBarState(
    val buttons: List<Ankrimento.AnswerButton>,
    /** Done and Postpone: visible, as desktop shows them in the reviewer bar. */
    val topicActions: List<ReaderTool>,
    /** "Revisit in…" choices, under one chip, as desktop keeps them in a menu. */
    val revisitOptions: List<ReaderTool>,
    /** Returns to the previous element, as SuperMemo's Back; null when there is none to return to. */
    val onBack: (() -> Unit)?,
    /** An item's Show answer, offered instead of the answers until it is used. */
    val reveal: (() -> Unit)? = null,
    /** A topic's More / Same / Less, shown as SuperMemo's Next with Sooner and Later beside it. */
    val topic: Boolean = false,
    val onAnswer: (Rating) -> Unit,
)

/** A paged document's position: [page] of [pages], 1-based; [onPage] goes to another page. */
class ReaderPager(
    val page: Int,
    val pages: Int,
    val onPage: (Int) -> Unit,
)

/**
 * Where the reader is in the document: [fraction] of it read, [label] for where ("Chapter 3 of 12"), and under it
 * [detail] when there is one (desktop's "Viewed 40% · Marked read 10%").
 */
class ReaderProgress(
    val fraction: Float,
    val label: String,
    val detail: String? = null,
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
    timer: (@Composable () -> Unit)? = null,
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
                        timer?.invoke()
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
                Column(Modifier.weight(1f)) {
                    Text(
                        progress.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    progress.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
        Column(Modifier.navigationBarsPadding()) {
            if (state.topic && state.buttons.size == 3) {
                TopicBar(state)
            } else {
                if (state.topicActions.isNotEmpty() || state.revisitOptions.isNotEmpty()) TopicActionRow(state)
                AnswerRow(state)
            }
        }
    }
}

/**
 * A topic read in a session, as SuperMemo's Next: one large button reads on and keeps the topic's
 * pace (desktop's Same); Sooner and Later beside it change how often it returns (More and Less).
 * All three schedule it, so the line above says when it comes back. Done, Postpone and Revisit,
 * each with what it does, are in the Topic menu.
 */
@Composable
private fun TopicBar(state: ReviewBarState) {
    val (sooner, next, later) = state.buttons.sortedBy { it.rating.number }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (next.due.isNullOrEmpty()) "" else stringResource(R.string.ankrimento_topic_returns, next.due),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state.topicActions.isNotEmpty() || state.revisitOptions.isNotEmpty()) TopicMenu(state)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.onBack?.invoke() }, enabled = state.onBack != null) {
            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_previous_element))
        }
        val side = Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp)
        // Narrow phones and longer translations: little side padding, and the label may take two lines.
        val padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        FilledTonalButton(onClick = { state.onAnswer(sooner.rating) }, modifier = side.weight(1f), contentPadding = padding) {
            PaceLabel(stringResource(R.string.ankrimento_topic_sooner), sooner.due)
        }
        Button(onClick = { state.onAnswer(next.rating) }, modifier = side.weight(1.6f), contentPadding = padding) {
            Text(stringResource(R.string.ankrimento_topic_next), fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center)
        }
        FilledTonalButton(onClick = { state.onAnswer(later.rating) }, modifier = side.weight(1f), contentPadding = padding) {
            PaceLabel(stringResource(R.string.ankrimento_topic_later), later.due)
        }
    }
}

@Composable
private fun PaceLabel(
    label: String,
    due: String?,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!due.isNullOrEmpty()) Text(due, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Done, Postpone and Revisit in…, each with a line on what it does. */
@Composable
private fun TopicMenu(state: ReviewBarState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.ankrimento_topic_menu))
            Icon(IncrementoIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Rows without an icon keep its room, so the labels line up.
            val iconRoom = state.topicActions.any { it.icon != null }
            state.topicActions.forEach { action ->
                DropdownMenuItem(
                    text = { MenuLine(action.label, action.description) },
                    leadingIcon =
                        action.icon?.let { icon -> { Icon(icon, contentDescription = null) } }
                            ?: if (iconRoom) ({ Spacer(Modifier.size(24.dp)) }) else null,
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
            if (state.revisitOptions.isNotEmpty()) {
                if (state.topicActions.isNotEmpty()) HorizontalDivider()
                MenuLine(
                    stringResource(R.string.ankrimento_revisit),
                    stringResource(R.string.ankrimento_revisit_hint),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                state.revisitOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            option.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuLine(
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A topic's own actions, a thumb's reach above the answers: Done, Postpone, Revisit in…. */
@Composable
private fun TopicActionRow(state: ReviewBarState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 56.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.topicActions.forEach { action ->
            AssistChip(
                onClick = action.onClick,
                label = { Text(action.label) },
                leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) } },
            )
        }
        if (state.revisitOptions.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { expanded = true },
                    label = { Text(stringResource(R.string.ankrimento_revisit)) },
                    trailingIcon = { Icon(IncrementoIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.revisitOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                expanded = false
                                option.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerRow(state: ReviewBarState) {
    run {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.onBack?.invoke() }, enabled = state.onBack != null) {
                Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_previous_element))
            }
            state.reveal?.let { reveal ->
                Button(
                    onClick = reveal,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).padding(horizontal = 4.dp),
                ) { Text(stringResource(R.string.ankrimento_show_answer), fontWeight = FontWeight.Medium) }
                return@Row
            }
            val buttons = state.buttons.sortedBy { it.rating.number }
            buttons.forEachIndexed { index, button ->
                // The usual answer stands out: Same among topics' choices, Good or Pass on items.
                val emphasized = if (buttons.size == 3) index == 1 else button.ease == 3
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

/** A future date for "Revisit in…", as yyyy-mm-dd; days before tomorrow cannot be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RevisitDateDialog(
    onDismiss: () -> Unit,
    onDate: (String) -> Unit,
) {
    val tomorrow =
        java.time.LocalDate
            .now()
            .plusDays(1)
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis =
                tomorrow
                    .plusMonths(1)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !java.time.Instant
                            .ofEpochMilli(utcTimeMillis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                            .isBefore(tomorrow)
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onDate(
                            java.time.Instant
                                .ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC)
                                .toLocalDate()
                                .toString(),
                        )
                    }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    ) { DatePicker(state = state) }
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
