// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.FocusTimer
import com.ichi2.anki.ankrimento.R

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

/** In the reader's top bar while a timer is running or paused; red in its last minute, as on desktop. */
@Composable
internal fun TimerChip(
    state: FocusTimer.State,
    onClick: () -> Unit,
) {
    val urgent = state.running && state.remaining <= 60
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Text(
            (if (state.running) "" else "‖ ") + clock(state.remaining),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Desktop's timer controls: its presets, the time left, start or pause, and reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimerSheet(
    state: FocusTimer.State,
    onPick: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.ankrimento_timer_title), style = MaterialTheme.typography.titleMedium)
            Text(
                clock(state.remaining),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                color =
                    if (state.running &&
                        state.remaining <= 60
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusTimer.PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = state.minutes == minutes,
                        enabled = !state.running,
                        onClick = { onPick(minutes) },
                        label = { Text(stringResource(R.string.ankrimento_timer_minutes, minutes)) },
                    )
                }
            }
            Text(
                stringResource(R.string.ankrimento_timer_counts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                OutlinedButton(onClick = onReset, enabled = state.active) { Text(stringResource(R.string.ankrimento_timer_reset)) }
                Button(onClick = if (state.running) onPause else onStart) {
                    Text(
                        stringResource(
                            when {
                                state.running -> R.string.ankrimento_timer_pause
                                state.active -> R.string.ankrimento_timer_resume
                                else -> R.string.ankrimento_timer_start
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Desktop's end-of-timer summary: what this run counted, then today so far.
 *
 * @param todayPages pages read today, or null while not known
 * @param todayCards cards reviewed today, or null while not known
 */
@Composable
internal fun TimerSummaryDialog(
    summary: FocusTimer.Summary,
    todayPages: Int?,
    todayCards: Int?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ankrimento_timer_complete, summary.minutes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ankrimento_timer_this), style = MaterialTheme.typography.labelLarge)
                if (summary.cards == 0 && summary.pdfPages == 0 && summary.epubPages == 0) {
                    Text(stringResource(R.string.ankrimento_timer_empty))
                } else {
                    Text(pluralStringResource(R.plurals.ankrimento_timer_cards, summary.cards, summary.cards))
                    for ((kind, pages, documents) in listOf(
                        Triple("PDF", summary.pdfPages, summary.pdfDocuments),
                        Triple("EPUB", summary.epubPages, summary.epubDocuments),
                    )) {
                        if (pages > 0) {
                            Text(
                                pluralStringResource(R.plurals.ankrimento_timer_pages, pages, pages, kind) + " " +
                                    pluralStringResource(R.plurals.ankrimento_timer_documents, documents, documents),
                            )
                        }
                    }
                }
                if (todayPages != null && todayCards != null) {
                    Text(
                        stringResource(R.string.ankrimento_timer_today),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        stringResource(
                            R.string.ankrimento_timer_today_summary,
                            pluralStringResource(R.plurals.ankrimento_timer_today_pages, todayPages, todayPages),
                            pluralStringResource(R.plurals.ankrimento_timer_cards, todayCards, todayCards),
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
