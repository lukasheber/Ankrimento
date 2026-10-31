// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * A reader's WebView whose text-selection menu starts with the reader's own
 * actions (Extract, Highlight), where a thumb that has just selected text
 * already is; desktop puts them on shortcuts and a context menu.
 *
 * A single tap on the page that is not on a link, nor ends a selection,
 * goes to [onTap]: readers use it to show or hide their bars.
 */
internal class ReaderWebView(
    context: Context,
) : WebView(context) {
    /**
     * An action on the selected text: [script] runs in the page while the
     * selection still exists; [onResult] gets its JSON-encoded result.
     */
    class SelectionAction(
        val label: String,
        val script: String,
        val onResult: (String) -> Unit = {},
    )

    var selectionActions: List<SelectionAction> = emptyList()

    var onTap: (() -> Unit)? = null

    private var selecting = false
    private var selectingAtTouchDown = false

    private val taps =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    if (selecting || selectingAtTouchDown) return false
                    val hit = hitTestResult.type
                    if (hit == HitTestResult.SRC_ANCHOR_TYPE || hit == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
                    onTap?.invoke()
                    return onTap != null
                }
            },
        )

    @Suppress("ClickableViewAccessibility") // Taps still reach the page; this only observes them.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) selectingAtTouchDown = selecting
        taps.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun startActionMode(
        callback: ActionMode.Callback,
        type: Int,
    ): ActionMode? = super.startActionMode(SelectionCallback(callback), type)

    private inner class SelectionCallback(
        private val inner: ActionMode.Callback,
    ) : ActionMode.Callback2() {
        override fun onCreateActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean {
            val created = inner.onCreateActionMode(mode, menu)
            selecting = true
            selectionActions.forEachIndexed { index, action ->
                menu.add(Menu.NONE, FIRST_ITEM_ID + index, index, action.label)
            }
            return created
        }

        override fun onPrepareActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean = inner.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(
            mode: ActionMode,
            item: MenuItem,
        ): Boolean {
            val action = selectionActions.getOrNull(item.itemId - FIRST_ITEM_ID) ?: return inner.onActionItemClicked(mode, item)
            // Close the menu only after the script ran: closing it clears the selection.
            evaluateJavascript(action.script) { result ->
                action.onResult(result)
                mode.finish()
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selecting = false
            inner.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(
            mode: ActionMode,
            view: View,
            outRect: Rect,
        ) {
            if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        const val FIRST_ITEM_ID = 0x1e00
    }
}
