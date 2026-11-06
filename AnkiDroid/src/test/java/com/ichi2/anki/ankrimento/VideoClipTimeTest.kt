// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import com.ichi2.anki.ankrimento.ui.clipTime
import com.ichi2.anki.ankrimento.ui.parseClipTime
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class VideoClipTimeTest {
    @Test
    fun `times are read as desktop's clip dialog takes them`() {
        assertThat(parseClipTime("1:05"), equalTo(65.0))
        assertThat(parseClipTime("1:02:03"), equalTo(3723.0))
        assertThat(parseClipTime("123s"), equalTo(123.0))
        assertThat(parseClipTime(" 90 "), equalTo(90.0))
        assertThat(parseClipTime("1:75"), equalTo(null))
        assertThat(parseClipTime("abc"), equalTo(null))
        assertThat(parseClipTime(""), equalTo(null))
        assertThat(parseClipTime("-5"), equalTo(null))
    }

    @Test
    fun `times are written as desktop writes them`() {
        assertThat(clipTime(65.4), equalTo("1:05"))
        assertThat(clipTime(3723.0), equalTo("1:02:03"))
        assertThat(clipTime(0.0), equalTo("0:00"))
    }
}
