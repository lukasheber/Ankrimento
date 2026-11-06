// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * A region of a reader's page as a PNG data URL, for a snapshot card (desktop grabs its view's pixels).
 * The page is copied from the screen rather than redrawn: a PDF page is drawn by the GPU.
 */
internal object RegionSnapshot {
    /** The viewers' own minimum (5 pixels). */
    private const val MIN_PX = 5

    /** Lets the layer drawn over the page (the box being dragged) go before the copy. */
    private const val SETTLE_MS = 150L

    /** [box] in [view]'s pixels, or null when it is too small or the screen could not be copied. */
    suspend fun capture(
        activity: Activity,
        view: View,
        box: Rect,
    ): String? {
        val left = box.left.toInt().coerceIn(0, view.width)
        val top = box.top.toInt().coerceIn(0, view.height)
        val right = box.right.toInt().coerceIn(0, view.width)
        val bottom = box.bottom.toInt().coerceIn(0, view.height)
        if (right - left < MIN_PX || bottom - top < MIN_PX) return null
        delay(SETTLE_MS)
        val region = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // PixelCopy of a window needs Android 8: the view draws itself instead (text and images; a page
            // the GPU draws, as a PDF's canvas, may come out blank).
            Canvas(region).apply { translate(-left.toFloat(), -top.toFloat()) }.also(view::draw)
            return encode(region)
        }
        val origin = IntArray(2).also { view.getLocationInWindow(it) }
        val source = android.graphics.Rect(origin[0] + left, origin[1] + top, origin[0] + right, origin[1] + bottom)
        val copied =
            suspendCancellableCoroutine { done ->
                PixelCopy.request(activity.window, source, region, { result ->
                    done.resumeWith(Result.success(result == PixelCopy.SUCCESS))
                }, Handler(activity.mainLooper))
            }
        if (!copied) {
            region.recycle()
            return null
        }
        return encode(region)
    }

    private suspend fun encode(region: Bitmap): String =
        withContext(Dispatchers.Default) {
            val bytes =
                java.io.ByteArrayOutputStream().use { out ->
                    region.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            region.recycle()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
}
