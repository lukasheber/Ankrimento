// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The extract sheet's note type: desktop's setting when set, otherwise the last one used for that kind of document. */
@RunWith(AndroidJUnit4::class)
class ExtractNoteTypesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun draft(configured: Boolean) =
        Ankrimento.ExtractDraft(
            fields = listOf("", ""),
            noteTypes = listOf("Basic", "Cloze", "Basic (and reversed card)"),
            noteType = "Basic",
            isTopic = false,
            priority = 50.0,
            noteTypeConfigured = configured,
        )

    @Test
    fun `each kind of document keeps the note type last used for it`() {
        ExtractNoteTypes.remember(context, "pdf", "Cloze")
        ExtractNoteTypes.remember(context, "epub", "Basic (and reversed card)")

        assertEquals("Cloze", ExtractNoteTypes.choose(context, "pdf", draft(configured = false)).noteType)
        assertEquals("Basic (and reversed card)", ExtractNoteTypes.choose(context, "epub", draft(configured = false)).noteType)
        assertEquals("Basic", ExtractNoteTypes.choose(context, "web", draft(configured = false)).noteType)
    }

    @Test
    fun `desktops extract note type setting wins`() {
        ExtractNoteTypes.remember(context, "pdf", "Cloze")
        assertEquals("Basic", ExtractNoteTypes.choose(context, "pdf", draft(configured = true)).noteType)
    }

    @Test
    fun `a remembered type that no longer exists is ignored`() {
        ExtractNoteTypes.remember(context, "video", "Deleted type")
        assertEquals("Basic", ExtractNoteTypes.choose(context, "video", draft(configured = false)).noteType)
    }
}
