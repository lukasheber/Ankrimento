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
import androidx.compose.material3.MaterialTheme
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
import com.ichi2.anki.ankrimento.R

/** An entry of the session card's menu. */
internal class SessionCardAction(
    val icon: ImageVector,
    val label: Int,
    val onClick: () -> Unit,
)

/**
 * The deck list's fixed way into incremental learning: the session deck
 * itself. With cards left its button continues the session; empty, it starts one.
 * Settings and a menu of Incremento's other places sit beside it.
 *
 * @param remaining cards left in the session deck, or null before it is known
 */
@Composable
internal fun SessionEntryCard(
    remaining: Int?,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    actions: List<SessionCardAction>,
) {
    val inProgress = remaining != null && remaining > 0
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
                    if (inProgress) {
                        pluralStringResource(R.plurals.ankrimento_session_remaining, remaining, remaining)
                    } else {
                        stringResource(R.string.ankrimento_session_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onStudy) {
                Text(stringResource(if (inProgress) R.string.ankrimento_session_continue else R.string.ankrimento_session_start))
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
