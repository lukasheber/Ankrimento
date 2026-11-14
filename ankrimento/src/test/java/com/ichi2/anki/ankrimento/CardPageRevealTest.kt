// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A topic asks its question first, as desktop's reviewer does, only when its answer side hides something. */
class CardPageRevealTest {
    @Test
    fun `a cloze topic hides its answer`() {
        val question = "<style>.card { color: red }</style>Water boils at <span class=cloze>[...]</span> degrees."
        val answer = "<style>.card { color: red }</style>Water boils at <span class=cloze>100</span> degrees."
        assertTrue(CardPage.answerHidesMore(question, answer))
    }

    @Test
    fun `a topic with a back hides it`() {
        assertTrue(CardPage.answerHidesMore("Batch one", "Batch one<hr id=answer>uno"))
    }

    @Test
    fun `an extract shows everything on its question side`() {
        // Basic's answer repeats the front under a rule; the back is empty.
        val front =
            "Paragraph 1: incremental reading&nbsp;keeps <b>many</b> articles in rotation.<br>" +
                "<a onclick=\"pycmd('incremento_open_source:1')\">Page 4</a>"
        assertFalse(CardPage.answerHidesMore(front, "$front\n\n<hr id=answer>\n\n"))
    }

    @Test
    fun `scripts and spacing are not something to reveal`() {
        assertFalse(CardPage.answerHidesMore("<p>Text</p>", "<script>var a = 1;</script>  <p>Text</p>\n"))
    }
}
