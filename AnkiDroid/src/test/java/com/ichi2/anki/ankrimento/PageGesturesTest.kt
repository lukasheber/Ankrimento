// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageGesturesTest {
    @Test
    fun `the end of a chapter is found from the window's scroll position`() {
        val script = PageGestures.script("next()", "previous()", PageGestures.Swipe.SCREEN, "next", "previous")

        // In some books (upstream's `overflow-x: hidden` body) the page scrolls while
        // document.scrollingElement.scrollTop stays 0: the pull past the end never started.
        assertThat(script, containsString("window.scrollY"))
        assertThat(script, not(containsString("d.scrollTop + window.innerHeight")))
    }

    @Test
    fun `in paged mode a swipe or an edge tap turns a page that starts on a whole line`() {
        val script = PageGestures.script("next()", "previous()", PageGestures.Swipe.SCREEN, "next", "previous")

        assertThat(script, containsString("window.__ankrimentoPaged"))
        // The line cut by the screen's edge starts the next page.
        assertThat(script, containsString("caretRangeFromPoint"))
        // An edge tap turns the page instead of showing or hiding the bars.
        assertThat(script, containsString("window.__ankrimentoPlainTap = false"))
    }
}
