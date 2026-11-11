// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R

/**
 * A local file card: the file's name, what it is and the note, with what a phone can do with it. Desktop's dock reveals
 * the file in Finder or Explorer and opens it in the default app; a phone has no folder to reveal, so the main action
 * is Open with… (any app that handles the type) and Share…. A card made on desktop that only points at a path on that
 * computer says so, and offers to keep a copy here instead.
 */
@Composable
internal fun LocalFileScreen(
    file: Ankrimento.LocalFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                IncrementoIcons.Document,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(file.name.ifEmpty { file.title }, style = MaterialTheme.typography.headlineSmall)
            if (file.available) {
                Text(
                    stringResource(
                        R.string.ankrimento_local_file_details,
                        Formatter.formatShortFileSize(LocalContext.current, file.size),
                        file.mime,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.note.isNotBlank()) {
                SelectionContainer {
                    Text(file.note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                file.available && file.canOpen -> {
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ankrimento_local_file_open)) }
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ankrimento_local_file_share)) }
                    TextButton(onClick = onAttach) { Text(stringResource(R.string.ankrimento_local_file_replace)) }
                }
                file.available -> {
                    Text(stringResource(R.string.ankrimento_local_file_blocked), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onAttach) { Text(stringResource(R.string.ankrimento_local_file_replace)) }
                }
                file.mode == "reference" -> {
                    Text(stringResource(R.string.ankrimento_local_file_on_computer), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(file.originalPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    Button(
                        onClick = onAttach,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ankrimento_local_file_attach)) }
                }
                else -> {
                    Text(stringResource(R.string.ankrimento_local_file_not_yet), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = onAttach,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ankrimento_local_file_attach)) }
                }
            }
        }
    }
}
