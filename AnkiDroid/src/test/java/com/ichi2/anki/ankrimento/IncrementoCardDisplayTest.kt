// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.IncrementoCardDisplay
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

/** Incremento's learning screen shows items as AnkiDroid's study screen renders them. */
@RunWith(AndroidJUnit4::class)
class IncrementoCardDisplayTest : RobolectricTest() {
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Test
    fun `sound becomes AnkiDroid's play button`() =
        runTest {
            val card = addBasicNote("Listen [sound:a.mp3]", "Back").firstCard(col)

            val question = IncrementoCardDisplay.question(card)

            assertThat(question, containsString("playsound:q:0"))
            assertThat(question, not(containsString("[anki:play")))
        }

    @Test
    fun `a typed answer gets AnkiDroid's box, then Anki's comparison`() =
        runTest {
            val card = addBasicWithTypingNote("Capital of France?", "Paris").firstCard(col)

            val question = IncrementoCardDisplay.question(card)
            assertThat(question, containsString("""id="typeans""""))
            assertThat(question, containsString("ankidroid.onTypeAnswerKeyDown"))

            val answer = IncrementoCardDisplay.answer(card, "Pariss")
            assertThat(answer, containsString("typeGood"))
            assertThat(answer, containsString("typeBad"))
            assertThat(answer, not(containsString("[[type:")))
        }
}
