// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Desktop's PDF snapshot on a touch screen: over the page, a finger drags the
 * box to keep as an image; the page under the layer doesn't scroll or turn
 * meanwhile. [onBox] gets the box in the layer's pixels (the page's, as the
 * layer covers it exactly).
 */
@Composable
internal fun BoxScope.SnapshotLayer(
    onBox: (Rect) -> Unit,
    onCancel: () -> Unit,
) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val outline = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { point ->
                        start = point
                        end = point
                    },
                    onDrag = { change, _ -> end = change.position },
                    onDragEnd = {
                        val from = start
                        val to = end
                        start = null
                        end = null
                        if (from != null && to != null) {
                            onBox(Rect(minOf(from.x, to.x), minOf(from.y, to.y), maxOf(from.x, to.x), maxOf(from.y, to.y)))
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val from = start ?: return@Canvas
            val to = end ?: return@Canvas
            val topLeft = Offset(minOf(from.x, to.x), minOf(from.y, to.y))
            val size = Size(kotlin.math.abs(to.x - from.x), kotlin.math.abs(to.y - from.y))
            drawRect(outline.copy(alpha = 0.18f), topLeft, size)
            drawRect(outline, topLeft, size, style = Stroke(2.dp.toPx()))
        }
    }
    FilledTonalButton(
        onClick = onCancel,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
    ) { Text(stringResource(android.R.string.cancel)) }
}
