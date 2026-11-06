// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/**
 * Desktop's "Create Mobile Video Clip": a start and an end, the start at the moment playing and the end
 * 30 seconds on (within the video), typed as desktop takes them ("1:05", "1:02:03" or "65s").
 */
@Composable
internal fun VideoClipDialog(
    now: Double,
    duration: Double,
    onCut: (start: Double, end: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstStart = if (duration > 0) now.coerceIn(0.0, (duration - 1).coerceAtLeast(0.0)) else now.coerceAtLeast(0.0)
    val firstEnd = if (duration > 0) minOf(duration, firstStart + DEFAULT_LENGTH) else firstStart + DEFAULT_LENGTH
    var start by remember { mutableStateOf(clipTime(firstStart)) }
    var end by remember { mutableStateOf(clipTime(firstEnd)) }
    val startSeconds = parseClipTime(start)
    val endSeconds = parseClipTime(end)
    val valid = startSeconds != null && endSeconds != null && endSeconds > startSeconds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ankrimento_video_clip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ankrimento_video_clip_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.ankrimento_video_clip_start)) },
                    singleLine = true,
                    isError = startSeconds == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text(stringResource(R.string.ankrimento_video_clip_end)) },
                    singleLine = true,
                    isError = endSeconds == null || (startSeconds != null && endSeconds <= startSeconds),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCut(startSeconds!!, endSeconds!!) }, enabled = valid) {
                Text(stringResource(R.string.ankrimento_video_clip_cut))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Desktop's default clip length. */
private const val DEFAULT_LENGTH = 30.0

/** Seconds as desktop writes a time ("1:05", "1:02:03"). */
fun clipTime(seconds: Double): String {
    val whole = seconds.toLong().coerceAtLeast(0)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val rest = whole % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** A time typed as desktop takes it: "m:ss", "h:mm:ss", "123s" or plain seconds; null when it is none of them. */
fun parseClipTime(text: String): Double? {
    val value = text.trim().lowercase()
    if (value.isEmpty()) return null
    if (value.endsWith("s")) {
        return value
            .dropLast(1)
            .trim()
            .toDoubleOrNull()
            ?.takeIf { it >= 0 }
    }
    val parts = value.split(":")
    if (parts.size > 3 || parts.any { it.isBlank() }) return null
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    if (numbers.any { it < 0 } || numbers.drop(1).any { it >= 60 }) return null
    return numbers.fold(0.0) { total, part -> total * 60 + part }
}
