// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R

/**
 * Desktop's paired captions over the video: the target language's line, and the reference language's
 * under it, smaller, as desktop's player shows them.
 */
@Composable
internal fun BoxScope.CaptionOverlay(
    target: String?,
    reference: String?,
) {
    if (target.isNullOrBlank() && reference.isNullOrBlank()) return
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!target.isNullOrBlank()) {
            Text(target, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        if (!reference.isNullOrBlank()) {
            Text(
                reference,
                color = Color(0xFFD0D0D0),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A slot's text at [seconds], or null. */
internal fun Ankrimento.SubtitleTrack.textAt(seconds: Double): String? = cues.firstOrNull { seconds >= it.start && seconds < it.end }?.text

/**
 * Desktop's subtitle dialog on the phone: the target and the reference language, each from an SRT or
 * WebVTT file, shown or hidden, or removed. Desktop's download of a video's own tracks needs yt-dlp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitlesSheet(
    subtitles: Ankrimento.Subtitles,
    showTarget: Boolean,
    showReference: Boolean,
    onShow: (slot: String, show: Boolean) -> Unit,
    onAdd: (slot: String) -> Unit,
    onRemove: (slot: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.ankrimento_video_subtitles), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.ankrimento_subtitles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            SlotRow("target", R.string.ankrimento_subtitle_target, subtitles.target, showTarget, onShow, onAdd, onRemove)
            HorizontalDivider()
            SlotRow("reference", R.string.ankrimento_subtitle_reference, subtitles.reference, showReference, onShow, onAdd, onRemove)
        }
    }
}

@Composable
private fun SlotRow(
    slot: String,
    title: Int,
    track: Ankrimento.SubtitleTrack,
    shown: Boolean,
    onShow: (String, Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = track.cues.isNotEmpty(), role = Role.Switch) { onShow(slot, !shown) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(
                    track.label.ifEmpty { stringResource(R.string.ankrimento_subtitle_none) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = shown && track.cues.isNotEmpty(), onCheckedChange = null, enabled = track.cues.isNotEmpty())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onAdd(slot) }) { Text(stringResource(R.string.ankrimento_subtitle_add)) }
            if (track.label.isNotEmpty()) {
                TextButton(
                    onClick = { onRemove(slot) },
                ) { Text(stringResource(R.string.ankrimento_subtitle_remove)) }
            }
        }
    }
}
