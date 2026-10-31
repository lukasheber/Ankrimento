// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/**
 * The deck list's fixed way into incremental learning: the session deck
 * itself. With cards left it continues the session; empty, it starts one.
 *
 * @param remaining cards left in the session deck, or null before it is known
 */
@Composable
internal fun SessionEntryCard(
    remaining: Int?,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
) {
    val inProgress = remaining != null && remaining > 0
    ElevatedCard(
        onClick = onStudy,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
            IconButton(onClick = onLibrary) {
                Icon(IncrementoIcons.Book, contentDescription = stringResource(R.string.ankrimento_library))
            }
            IconButton(onClick = onSettings) {
                Icon(IncrementoIcons.Settings, contentDescription = stringResource(R.string.ankrimento_settings))
            }
        }
    }
}
