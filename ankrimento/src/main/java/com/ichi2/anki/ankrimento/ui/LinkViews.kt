// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/** A footnote shown over the page, so reading goes on where it was; Go to note follows the link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteSheet(
    text: String,
    onGoToNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onGoToNote) { Text(stringResource(R.string.ankrimento_epub_go_to_note)) }
            }
        }
    }
}

/** Over the page after a link was followed: back to where it was tapped. */
@Composable
internal fun LinkBackChip(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onBack,
        label = { Text(stringResource(R.string.ankrimento_epub_link_back)) },
        leadingIcon = { Icon(IncrementoIcons.Back, contentDescription = null) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = AssistChipDefaults.assistChipElevation(elevation = 6.dp),
        modifier = modifier.padding(16.dp),
    )
}
