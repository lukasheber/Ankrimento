// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.ichi2.anki.ankrimento.Ankrimento

/**
 * Desktop's "extract from the reviewer card" on AnkiDroid's study screens: an Extract item on the
 * selected text's floating toolbar, while Incremento runs.
 */
object IncrementoSelection {
    private const val EXTRACT_ID = 0x416b72 // "Akr": an id no other selection item uses

    /** The selected text of a card's page, as JSON text. */
    const val SELECTION_JS = "String(window.getSelection ? window.getSelection() : '')"

    /** [callback] with Extract added for a floating selection, or [callback] itself. */
    fun wrap(
        callback: ActionMode.Callback,
        type: Int,
        onExtract: () -> Unit,
    ): ActionMode.Callback {
        if (type != ActionMode.TYPE_FLOATING || !Ankrimento.isStarted) return callback
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(
                mode: ActionMode,
                menu: Menu,
            ): Boolean {
                val created = callback.onCreateActionMode(mode, menu)
                menu.add(Menu.NONE, EXTRACT_ID, 0, com.ichi2.anki.ankrimento.R.string.ankrimento_epub_extract)
                return created
            }

            override fun onPrepareActionMode(
                mode: ActionMode,
                menu: Menu,
            ) = callback.onPrepareActionMode(mode, menu)

            override fun onActionItemClicked(
                mode: ActionMode,
                item: MenuItem,
            ): Boolean {
                if (item.itemId != EXTRACT_ID) return callback.onActionItemClicked(mode, item)
                onExtract()
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)

            override fun onGetContentRect(
                mode: ActionMode,
                view: View,
                outRect: android.graphics.Rect,
            ) {
                if (callback is ActionMode.Callback2) {
                    callback.onGetContentRect(
                        mode,
                        view,
                        outRect,
                    )
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
            }
        }
    }
}
