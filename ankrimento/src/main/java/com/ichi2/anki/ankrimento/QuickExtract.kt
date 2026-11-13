// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import com.ichi2.anki.ankrimento.ui.ExtractChoice

/**
 * What "Extract in one tap" adds without the sheet: selected text becomes a topic, a cloze (or text whose answer is
 * already filled in) an item. Incremento's default for a new extract is an item with an empty answer, which is not a
 * card that can be added as it stands, so it is added as a topic: the choice the sheet would offer for plain text.
 */
internal object QuickExtract {
    /** The extract to add for [draft], or null when nothing was selected. */
    fun choice(draft: Ankrimento.ExtractDraft): ExtractChoice? {
        val text = draft.fields.firstOrNull()
        if (text.isNullOrBlank()) return null
        val complete = "{{c" in text || draft.fields.drop(1).any { it.isNotBlank() }
        return ExtractChoice(draft.fields, draft.noteType, isTopic = draft.isTopic || !complete, priority = draft.priority)
    }
}
