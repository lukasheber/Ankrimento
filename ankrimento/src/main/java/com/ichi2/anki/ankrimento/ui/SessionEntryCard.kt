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
import androidx.compose.ui.unit.dp
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
 * @param state where the day stands
 * @param plan the day's counts for the progress line; null without the daily plan
 */
@Composable
internal fun SessionEntryCard(
    state: DayState,
    plan: TodayPlan?,
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
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ankrimento_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    stateText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plan != null && plan.due + plan.done > 0) {
                    LinearProgressIndicator(
                        progress = { plan.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                    )
                    Text(
                        stringResource(R.string.ankrimento_today_progress, plan.done, plan.due),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
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
    }
}

@Composable
private fun stateText(state: DayState): String =
    when (state) {
        is DayState.Continue -> pluralStringResource(R.plurals.ankrimento_session_remaining, state.remaining, state.remaining)
        is DayState.Start -> pluralStringResource(R.plurals.ankrimento_today_due, state.due, state.due)
        is DayState.Done -> stringResource(R.string.ankrimento_today_done)
        is DayState.Classic ->
            state.remaining?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ankrimento_session_remaining, it, it) }
                ?: stringResource(R.string.ankrimento_session_none)
    }

private fun buttonLabel(state: DayState): Int =
    when (state) {
        is DayState.Continue -> R.string.ankrimento_session_continue
        is DayState.Start -> R.string.ankrimento_today_start
        is DayState.Done -> R.string.ankrimento_study_ahead
        is DayState.Classic ->
            if ((state.remaining ?: 0) > 0) R.string.ankrimento_session_continue else R.string.ankrimento_session_start
    }
