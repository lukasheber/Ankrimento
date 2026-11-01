// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingPreferencesTest {
    @Test
    fun `margins outrank upstream's page padding`() {
        val script = ReadingPreferences(margins = ReadingPreferences.Margins.WIDE).script()

        // Upstream's page style pads `html.incremento-epub-scaled body` with !important; a plain
        // `body` rule loses to it whatever the margins chosen. The same selector, added later, wins.
        assertThat(script, containsString("html.incremento-epub-scaled body {"))
    }

    @Test
    fun `line spacing reaches text wrapped in the book's own blocks`() {
        val script = ReadingPreferences(spacing = ReadingPreferences.Spacing.RELAXED).script()

        // Books wrap paragraphs in a `div` with its own line-height (`.quadro-de-texto-b-sico
        // { line-height: 1.2 }`): paragraphs inheriting would take the div's, not the body's.
        val inheriting =
            Regex("([^}']*)\\{ line-height: inherit")
                .find(script)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        assertThat(inheriting, containsString("body div"))
    }
}
