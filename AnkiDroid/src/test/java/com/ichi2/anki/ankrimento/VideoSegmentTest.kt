// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import com.ichi2.anki.ankrimento.ui.SegmentDraft
import com.ichi2.anki.ankrimento.ui.clampSegment
import com.ichi2.anki.ankrimento.ui.clockText
import com.ichi2.anki.ankrimento.ui.initialSegment
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class VideoSegmentTest {
    @Test
    fun `the first segment runs thirty seconds from the moment playing`() {
        assertThat(initialSegment(65.0, 600.0), equalTo(SegmentDraft(65.0, 95.0, 600.0)))
    }

    @Test
    fun `the first segment stops at the end of the video`() {
        assertThat(initialSegment(590.0, 600.0), equalTo(SegmentDraft(590.0, 600.0, 600.0)))
    }

    @Test
    fun `at the very end the first segment is the last thirty seconds`() {
        assertThat(initialSegment(600.0, 600.0), equalTo(SegmentDraft(570.0, 600.0, 600.0)))
        assertThat(initialSegment(20.0, 20.0), equalTo(SegmentDraft(0.0, 20.0, 20.0)))
    }

    @Test
    fun `without a known length the segment is thirty seconds on`() {
        assertThat(initialSegment(10.0, 0.0), equalTo(SegmentDraft(10.0, 40.0, 0.0)))
    }

    @Test
    fun `a handle dragged toward the other stops short of it instead of crossing`() {
        assertThat(clampSegment(120.0, 100.0, 600.0, movedStart = true), equalTo(99.0 to 100.0))
        assertThat(clampSegment(50.0, 40.0, 600.0, movedStart = false), equalTo(50.0 to 51.0))
    }

    @Test
    fun `the segment stays inside the video`() {
        assertThat(clampSegment(-5.0, 30.0, 600.0, movedStart = true), equalTo(0.0 to 30.0))
        assertThat(clampSegment(560.0, 700.0, 600.0, movedStart = false), equalTo(560.0 to 600.0))
        assertThat(clampSegment(600.0, 600.0, 600.0, movedStart = false), equalTo(599.0 to 600.0))
    }

    @Test
    fun `times are written as minutes and seconds, with hours when there are some`() {
        assertThat(clockText(65.4), equalTo("1:05"))
        assertThat(clockText(3723.0), equalTo("1:02:03"))
        assertThat(clockText(-3.0), equalTo("0:00"))
    }
}
