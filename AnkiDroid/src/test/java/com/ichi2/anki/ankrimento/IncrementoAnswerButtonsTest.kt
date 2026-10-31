// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ReviewerTest
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooper
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncrementoAnswerButtonsTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Test
    fun `an ordinary card after a topic gets AnkiDroid's labels back`() {
        addBasicNote()
        val reviewer = ReviewerTest.startReviewer(this)
        // A topic before it relabelled the buttons More / Same / Less (and Fail on items).
        reviewer.easeButton1!!.setup(0, 0, "Fail")
        reviewer.easeButton4!!.setup(0, 0, "Less")

        reviewer.displayCardAnswer()
        advanceRobolectricLooper()

        assertThat(reviewer.findViewById<TextView>(R.id.ease1).text.toString(), equalTo(reviewer.getString(CommonString.ease_button_again)))
        assertThat(reviewer.findViewById<TextView>(R.id.ease4).text.toString(), equalTo(reviewer.getString(CommonString.ease_button_easy)))
    }
}
