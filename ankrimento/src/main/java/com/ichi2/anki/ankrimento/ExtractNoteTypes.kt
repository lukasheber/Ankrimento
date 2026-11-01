// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import androidx.core.content.edit

/**
 * The note type an extract starts with. Desktop uses its extract note type setting, and without it
 * the Add Card window's last type; the phone keeps the last type per kind of document instead
 * ("pdf", "epub", "web", "video", "text", "item", a reviewer "card", a "scratch" card), so each kind
 * starts with what was used for it before.
 */
object ExtractNoteTypes {
    private const val PREFERENCES = "ankrimento_extract_note_types"

    fun choose(
        context: Context,
        kind: String,
        draft: Ankrimento.ExtractDraft,
    ): Ankrimento.ExtractDraft {
        if (draft.noteTypeConfigured) return draft
        val remembered = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(kind, null)
        return if (remembered != null && remembered in draft.noteTypes) draft.copy(noteType = remembered) else draft
    }

    fun remember(
        context: Context,
        kind: String,
        noteType: String,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(kind, noteType) }
    }
}
