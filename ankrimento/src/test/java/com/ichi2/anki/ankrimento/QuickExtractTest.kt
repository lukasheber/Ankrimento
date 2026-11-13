// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Extract in one tap" adds what was selected without the sheet: text becomes a topic, a cloze an item. Incremento's
 * own default for a new extract is an item with its answer empty, which must not stop the one tap from working.
 */
class QuickExtractTest {
    private fun draft(
        fields: List<String>,
        isTopic: Boolean,
    ) = Ankrimento.ExtractDraft(fields = fields, noteTypes = listOf("Basic"), noteType = "Basic", isTopic = isTopic, priority = 49.0)

    @Test
    fun `plain text is added as a topic even when new extracts are items by default`() {
        val choice = QuickExtract.choice(draft(listOf("Selected sentence.", ""), isTopic = false))!!

        assertTrue(choice.isTopic)
        assertEquals(listOf("Selected sentence.", ""), choice.fields)
        assertEquals("Basic", choice.noteType)
        assertEquals(49.0, choice.priority, 0.0)
    }

    @Test
    fun `plain text stays a topic when new extracts are topics`() {
        assertTrue(QuickExtract.choice(draft(listOf("Selected sentence.", ""), isTopic = true))!!.isTopic)
    }

    @Test
    fun `a cloze is added as an item`() {
        val choice = QuickExtract.choice(draft(listOf("The {{c1::mitochondria}} make energy.", ""), isTopic = false))!!

        assertFalse(choice.isTopic)
    }

    @Test
    fun `text with its answer filled in is added as an item`() {
        assertFalse(QuickExtract.choice(draft(listOf("What makes energy?", "Mitochondria"), isTopic = false))!!.isTopic)
    }

    @Test
    fun `nothing is added when nothing was selected`() {
        assertNull(QuickExtract.choice(draft(listOf("", ""), isTopic = false)))
        assertNull(QuickExtract.choice(draft(emptyList(), isTopic = false)))
    }
}
