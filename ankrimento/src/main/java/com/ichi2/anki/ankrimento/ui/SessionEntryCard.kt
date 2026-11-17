// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.DayState
import com.ichi2.anki.ankrimento.R
import com.ichi2.anki.ankrimento.TodayPlan

/** An entry of the session card's menu. */
internal class SessionCardAction(
    val icon: ImageVector,
    val label: Int,
    val onClick: () -> Unit,
)

/**
 * The deck list's fixed way into incremental learning. With the daily plan it shows the day: what is due, how much
 * is done, and one button for the next step: continue the session under way, start today's, or, once nothing is
 * due, study ahead. Settings and a menu of Incremento's other places sit beside it.
 *
 * A big sync with the computer shows here too, since it can take minutes: how many documents have arrived, with
 * its own bar, or, between syncs, how many are still to come.
 *
 * @param state where the day stands
 * @param plan the day's counts for the progress line; null without the daily plan
 * @param transfer documents a sync is moving now; null when none is
 * @param documentsLeft documents still to sync with the computer
 */
@Composable
internal fun SessionEntryCard(
    state: DayState,
    plan: TodayPlan?,
    transfer: Ankrimento.DocumentTransfer? = null,
    documentsLeft: Int = 0,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    actions: List<SessionCardAction>,
) {
    // Only the button studies. The card still takes taps, so none reach the deck list behind it.
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ankrimento_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    statusText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                countsText(state, plan)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state is DayState.Done) {
                // The day is complete: more is a choice, so it does not look like the day's main action.
                OutlinedButton(onClick = onStudy) { Text(stringResource(R.string.ankrimento_study_ahead)) }
            } else {
                Button(onClick = onStudy) { Text(stringResource(buttonLabel(state))) }
            }
            IconButton(onClick = onSettings) {
                Icon(IncrementoIcons.Settings, contentDescription = stringResource(R.string.ankrimento_settings))
            }
            Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }) {
                    Icon(IncrementoIcons.More, contentDescription = stringResource(R.string.ankrimento_more_options))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            leadingIcon = { Icon(action.icon, contentDescription = null) },
                            onClick = {
                                open = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }
        if (plan != null && plan.left + plan.done > 0) {
            // The day across the whole card, apart from the buttons.
            LinearProgressIndicator(
                progress = { plan.progress },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        }
        SyncLine(transfer, documentsLeft)
    }
}

/** Documents moving to or from the computer, with a bar; or how many are still to come; nothing when all are here. */
@Composable
private fun SyncLine(
    transfer: Ankrimento.DocumentTransfer?,
    documentsLeft: Int,
) {
    val text =
        when {
            transfer == null && documentsLeft > 0 ->
                pluralStringResource(R.plurals.ankrimento_sync_documents_left, documentsLeft, documentsLeft)
            transfer == null -> return
            transfer.incoming -> stringResource(R.string.ankrimento_sync_receiving_documents, transfer.done, transfer.total)
            else -> stringResource(R.string.ankrimento_sync_sending_documents, transfer.done, transfer.total)
        }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (transfer != null) {
            LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

/** One short line on where things stand. The numbers of the day are the line below it. */
@Composable
private fun statusText(state: DayState): String =
    when (state) {
        is DayState.Continue -> stringResource(R.string.ankrimento_session_in_progress)
        is DayState.Start -> pluralStringResource(R.plurals.ankrimento_today_due, state.left, state.left)
        is DayState.Done -> stringResource(R.string.ankrimento_today_done)
        is DayState.Classic ->
            if ((state.remaining ?: 0) > 0) {
                stringResource(R.string.ankrimento_session_in_progress)
            } else {
                stringResource(R.string.ankrimento_session_none)
            }
    }

/** The day's numbers, or what is left of a classic session; nothing when they would only repeat the line above. */
@Composable
private fun countsText(
    state: DayState,
    plan: TodayPlan?,
): String? =
    when {
        state is DayState.Classic -> state.remaining?.takeIf { it > 0 }?.let { stringResource(R.string.ankrimento_card_left, it) }
        plan == null || plan.left + plan.done == 0 -> null
        state is DayState.Start && plan.done == 0 -> null
        else -> stringResource(R.string.ankrimento_today_progress, plan.done, plan.left)
    }

private fun buttonLabel(state: DayState): Int =
    when (state) {
        is DayState.Continue -> R.string.ankrimento_session_continue
        is DayState.Start -> R.string.ankrimento_today_start
        is DayState.Done -> R.string.ankrimento_study_ahead
        is DayState.Classic ->
            if ((state.remaining ?: 0) > 0) R.string.ankrimento_session_continue else R.string.ankrimento_session_start
    }
