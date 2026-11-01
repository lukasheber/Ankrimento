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
}
