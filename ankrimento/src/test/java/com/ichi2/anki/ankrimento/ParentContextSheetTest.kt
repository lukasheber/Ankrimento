// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentContextSheetTest {
    private val fields = listOf("Title" to "<p>Spaced repetition</p>", "Notes" to "<b>kept</b>")

    @Test
    fun `the page shows the title and every field, each under its name`() {
        val page = ParentContextSheet.page(dark = true, title = "Spaced <repetition>", fields = fields)

        assertTrue(page.contains("<h3>Spaced &lt;repetition&gt;</h3>"))
        assertTrue(page.contains("<h4>Title</h4><p>Spaced repetition</p>"))
        assertTrue(page.contains("<h4>Notes</h4><b>kept</b>"))
    }

    @Test
    fun `the text colour is a css colour in the style, never part of the text`() {
        val dark = ParentContextSheet.page(dark = true, title = "T", fields = emptyList())
        val light = ParentContextSheet.page(dark = false, title = "T", fields = emptyList())

        assertTrue(dark.contains("color:#E6E1E5;"))
        assertTrue(light.contains("color:#1C1B1F;"))
        // The body holds only the title: no stray number where the colour was formatted into the wrong place.
        assertEquals("<body><h3>T</h3></body>", dark.substringAfter("</style></head>").substringBefore("</html>"))
        assertFalse(dark.contains("15131109"))
    }

    @Test
    fun `a percent sign in a field is left alone`() {
        val page = ParentContextSheet.page(dark = false, title = "100%", fields = listOf("Result" to "<p>90%</p>"))

        assertTrue(page.contains("<h3>100%</h3>"))
        assertTrue(page.contains("<p>90%</p>"))
    }
}
