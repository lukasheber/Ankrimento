// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/** Segments are at least this long, in seconds, so a segment always has something to play. */
const val MIN_SEGMENT_SECONDS = 1.0

/** A segment of a video being chosen: where it [start]s and [end]s, in seconds, within a video of [duration] (0 when unknown). */
data class SegmentDraft(
    val start: Double,
    val end: Double,
    val duration: Double,
)

/**
 * The segment a touch on the range slider describes, kept inside the video and at least [MIN_SEGMENT_SECONDS] long:
 * a handle dragged toward the other stops [MIN_SEGMENT_SECONDS] short of it instead of crossing it.
 */
fun clampSegment(
    start: Double,
    end: Double,
    duration: Double,
    movedStart: Boolean,
): Pair<Double, Double> {
    val top = if (duration > 0) duration else maxOf(end, start + MIN_SEGMENT_SECONDS)
    var s = start.coerceIn(0.0, top)
    var e = end.coerceIn(0.0, top)
    if (e - s < MIN_SEGMENT_SECONDS) {
        if (movedStart) {
            s = (e - MIN_SEGMENT_SECONDS).coerceAtLeast(0.0)
        } else {
            e = (s + MIN_SEGMENT_SECONDS).coerceAtMost(top)
            s = (e - MIN_SEGMENT_SECONDS).coerceAtLeast(0.0)
        }
    }
    if (e - s < MIN_SEGMENT_SECONDS) {
        // At an edge of a very short video: the whole of what is there.
        s = 0.0
        e = top
    }
    return s to e
}

/** The first segment offered when the bar opens: from [now], [length] seconds on, or the last [length] seconds at the video's end. */
fun initialSegment(
    now: Double,
    duration: Double,
    length: Double = 30.0,
): SegmentDraft {
    val start = now.coerceAtLeast(0.0)
    if (duration <= 0) return SegmentDraft(start, start + length, 0.0)
    if (start + MIN_SEGMENT_SECONDS > duration) return SegmentDraft((duration - length).coerceAtLeast(0.0), duration, duration)
    return SegmentDraft(start, minOf(start + length, duration), duration)
}

/** Seconds as m:ss, or h:mm:ss. */
fun clockText(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val (hours, minutes, secs) = Triple(total / 3600, total % 3600 / 60, total % 60)
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/**
 * Choosing a segment of the video for an extract, over the video: drag the two handles (the video follows the one
 * dragged), or play to a moment and take it as the start or the end; preview plays the segment and stops at its
 * end; one tap makes the extract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSegmentBar(
    segment: SegmentDraft,
    onRange: (start: Double, end: Double, movedStart: Boolean) -> Unit,
    onStartHere: () -> Unit,
    onEndHere: () -> Unit,
    onPreview: () -> Unit,
    onExtract: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 6.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.ankrimento_video_segment_title,
                        clockText(segment.start),
                        clockText(segment.end),
                        clockText(segment.end - segment.start),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(IncrementoIcons.Close, contentDescription = stringResource(R.string.ankrimento_video_segment_close))
                }
            }
            if (segment.duration > 0) {
                RangeSlider(
                    value = segment.start.toFloat()..segment.end.toFloat(),
                    onValueChange = { range ->
                        val movedStart = kotlin.math.abs(range.start - segment.start) > kotlin.math.abs(range.endInclusive - segment.end)
                        val (s, e) = clampSegment(range.start.toDouble(), range.endInclusive.toDouble(), segment.duration, movedStart)
                        onRange(s, e, movedStart)
                    },
                    valueRange = 0f..segment.duration.toFloat(),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tall = Modifier.heightIn(min = 48.dp)
                FilledTonalButton(onClick = onStartHere, modifier = tall.weight(1f), contentPadding = SMALL_PADDING) {
                    Text(stringResource(R.string.ankrimento_video_segment_start_here), maxLines = 1)
                }
                FilledTonalButton(onClick = onEndHere, modifier = tall.weight(1f), contentPadding = SMALL_PADDING) {
                    Text(stringResource(R.string.ankrimento_video_segment_end_here), maxLines = 1)
                }
                OutlinedButton(onClick = onPreview, modifier = tall.weight(1f), contentPadding = SMALL_PADDING) {
                    Text(stringResource(R.string.ankrimento_video_segment_preview), maxLines = 1)
                }
            }
            Button(
                onClick = onExtract,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.ankrimento_video_segment_extract)) }
        }
    }
}

private val SMALL_PADDING =
    androidx.compose.foundation.layout
        .PaddingValues(horizontal = 8.dp, vertical = 8.dp)
