// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The learning loop's bar, as a phone shows it: a topic reads on with Next (Sooner and Later beside it,
 * Done, Postpone and Revisit in its menu); an item is revealed, then graded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xxhdpi")
class LearningLoopBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    /** Desktop's topic buttons as the phone receives them: More, Same and Less on Hard, Good and Easy. */
    private val topicButtons =
        listOf(
            Ankrimento.AnswerButton(Rating.HARD, "More", 3, "11d"),
            Ankrimento.AnswerButton(Rating.GOOD, "Same", 3, "12d"),
            Ankrimento.AnswerButton(Rating.EASY, "Less", 3, "13d"),
        )

    private fun show(review: ReviewBarState) =
        compose.setContent {
            IncrementoTheme {
                ReaderScaffold(title = "A topic", subtitle = null, tools = emptyList(), menu = emptyList(), review = review, onBack = {}) {}
            }
        }

    @Test
    fun `a topic reads on with Next, and Sooner and Later change how often it returns`() {
        val answered = mutableListOf<Rating>()
        show(
            ReviewBarState(topicButtons, emptyList(), emptyList(), onBack = null, topic = true) { answered += it },
        )

        compose.onNodeWithText(text(R.string.ankrimento_topic_returns).format("12d")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ankrimento_topic_next)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.ankrimento_topic_sooner)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.ankrimento_topic_later)).assertIsDisplayed().performClick()
        compose.onNodeWithText("11d").assertIsDisplayed()
        compose.onNodeWithText("13d").assertIsDisplayed()

        // Next is desktop's Same; Sooner and Later are More and Less.
        assertEquals(listOf(Rating.GOOD, Rating.HARD, Rating.EASY), answered)
        // No Show answer on a topic: there is nothing to reveal.
        assertEquals(0, compose.onAllNodesWithText(text(R.string.ankrimento_show_answer)).fetchSemanticsNodes().size)
    }

    @Test
    fun `Done is on the bar itself and Postpone and Revisit wait under More, each saying what it does`() {
        val done = mutableListOf<String>()
        show(
            ReviewBarState(
                topicButtons,
                topicActions =
                    listOf(
                        ReaderTool("Done", IncrementoIcons.Check, "Stop reviewing it") { done += "done" },
                        ReaderTool("Postpone", description = "Not now") { done += "postpone" },
                    ),
                revisitOptions = listOf(ReaderTool("In 3 months") { done += "3 months" }),
                onBack = null,
                topic = true,
            ) {},
        )

        // Done needs no menu; the rest is not in the way until More is opened.
        compose.onAllNodesWithText("Not now").assertCountEquals(0)
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(text(R.string.ankrimento_topic_menu)).performClick()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ankrimento_revisit_hint)).assertIsDisplayed()
        compose.onNodeWithText("Postpone").performClick()
        compose.onNodeWithContentDescription(text(R.string.ankrimento_topic_menu)).performClick()
        compose.onNodeWithText("In 3 months").performClick()

        assertEquals(listOf("done", "postpone", "3 months"), done)
    }

    @Test
    fun `an item is revealed first, then graded`() {
        var revealed = false
        val itemButtons =
            listOf(
                Ankrimento.AnswerButton(Rating.AGAIN, "Fail", 1, null),
                Ankrimento.AnswerButton(Rating.GOOD, "Pass", 3, null),
            )
        show(ReviewBarState(itemButtons, emptyList(), emptyList(), onBack = null, reveal = { revealed = true }) {})

        compose.onNodeWithText(text(R.string.ankrimento_show_answer)).assertIsDisplayed().performClick()
        assertEquals(true, revealed)
        // Before the answer, no grades: they cannot be pressed by mistake.
        assertEquals(0, compose.onAllNodesWithText("Pass").fetchSemanticsNodes().size)
    }

    @Test
    fun `a revealed item shows its grades, the usual one included`() {
        val answered = mutableListOf<Rating>()
        val itemButtons =
            listOf(
                Ankrimento.AnswerButton(Rating.AGAIN, "Fail", 1, null),
                Ankrimento.AnswerButton(Rating.GOOD, "Pass", 3, null),
            )
        show(ReviewBarState(itemButtons, emptyList(), emptyList(), onBack = null) { answered += it })

        compose.onNodeWithText("Pass").assertIsDisplayed().performClick()
        compose.onNodeWithText("Fail").assertIsDisplayed().performClick()
        assertEquals(listOf(Rating.GOOD, Rating.AGAIN), answered)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h640dp-mdpi")
    fun `on a narrow phone in a longer language, the topic bar's labels are all shown`() {
        show(ReviewBarState(topicButtons, emptyList(), emptyList(), onBack = { }, topic = true) {})

        compose.onNodeWithText("Próximo tópico").assertIsDisplayed()
        compose.onNodeWithText("Antes").assertIsDisplayed()
        compose.onNodeWithText("Depois").assertIsDisplayed()
    }
}
