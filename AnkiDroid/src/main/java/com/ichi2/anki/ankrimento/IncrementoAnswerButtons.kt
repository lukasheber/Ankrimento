// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.Activity
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.CommonString
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.reviewer.AnswerButtons.Companion.getBackgroundColors
import com.ichi2.anki.reviewer.AnswerButtons.Companion.getTextColors

/**
 * Opens a card's reader when its question is shown, as desktop's reader docks
 * do, and records the answer the reader returns: in a session a topic is
 * reviewed in its reader, and the reviewer moves straight to the next card.
 *
 * Register it while the reviewer is created (it registers an activity result).
 *
 * @param leaveSession closes the reviewer, as its Back does
 */
internal class IncrementoReaders(
    private val viewer: AbstractFlashcardViewer,
    private val leaveSession: () -> Unit,
) {
    /**
     * The card whose reader is open, or that the reader returned to the
     * reviewer to show; redrawing that card does not reopen its reader.
     */
    private var openedCardId: CardId? = null

    private val launcher =
        viewer.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onReaderClosed)

    /** Tells Incremento a card's question is on screen (it times reviews from here), then opens its reader. */
    fun questionShown(cardId: CardId) {
        if (!Ankrimento.isStarted) return
        viewer.launchCatchingTask {
            val reader =
                withCol {
                    Ankrimento.questionShown(cardId)
                    Ankrimento.cardReader(cardId)
                }
            // Topic actions depend on the card just shown.
            viewer.invalidateOptionsMenu()
            if (cardId != openedCardId) open(cardId, reader)
        }
    }

    /** Opens [cardId]'s reader to review it, from the menu after "Show card". */
    fun reopen(cardId: CardId) {
        viewer.launchCatchingTask { open(cardId, withCol { Ankrimento.cardReader(cardId) }) }
    }

    private fun open(
        cardId: CardId,
        reader: String,
    ) {
        val intent =
            ReaderActivity.intent(viewer, reader, cardId, session = true)?.putExtra(ReaderActivity.EXTRA_QUESTION_SHOWN, true) ?: return
        openedCardId = cardId
        launcher.launch(intent)
    }

    private fun onReaderClosed(result: ActivityResult) {
        val cardId = result.data?.getLongExtra(ReaderActivity.EXTRA_CARD_ID, 0L)
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                // The card is done with; if it comes back, its reader opens again.
                openedCardId = null
                val rating = result.data?.getIntExtra(ReaderActivity.EXTRA_RATING, -1) ?: -1
                // Without a rating, a topic action rescheduled the card and the reviewer redraws itself.
                if (rating < 0 || cardId == null || viewer.currentCard?.id != cardId) return
                viewer.launchCatchingTask {
                    // Desktop's answer timing starts when the answer is shown.
                    withCol { Ankrimento.answerShown(cardId) }
                    viewer.answerCard(Rating.forNumber(rating))
                }
            }
            ReaderActivity.RESULT_LEAVE_SESSION -> leaveSession()
        }
    }
}

/**
 * Tells Incremento the answer is on screen, then shows its answer buttons in the legacy reviewer: More/Same/Less on
 * topics, and Fail/Pass on items when enabled. Ordinary cards keep
 * AnkiDroid's buttons.
 *
 * Each Incremento button keeps the color of the AnkiDroid button it replaces.
 *
 * @param ankiNextTimes Anki's next-interval text per ease, or null when hidden
 */
internal suspend fun AbstractFlashcardViewer.showIncrementoAnswerButtons(
    cardId: CardId,
    ankiNextTimes: List<String>?,
) {
    val buttons =
        withCol {
            Ankrimento.answerShown(cardId)
            Ankrimento.answerButtons(cardId)
        }
    val background = getBackgroundColors(this)
    val textColors = getTextColors(this)
    if (buttons == null) {
        // The reviewer relabels Hard and Good itself; Again and Easy keep whatever a topic gave them.
        easeButton1!!.setup(background[0], textColors[0], CommonString.ease_button_again)
        easeButton4!!.setup(background[3], textColors[3], CommonString.ease_button_easy)
        return
    }
    val easeButtons = listOf(easeButton1!!, easeButton2!!, easeButton3!!, easeButton4!!)
    easeButtons.forEachIndexed { index, easeButton ->
        val button = buttons.firstOrNull { it.rating.number == index }
        if (button == null) {
            easeButton.setVisibility(View.GONE)
            return@forEachIndexed
        }
        easeButton.setup(background[index], textColors[index], button.label)
        if (ankiNextTimes != null) {
            easeButton.nextTime = button.due ?: ankiNextTimes.getOrElse(button.ease - 1) { "" }
        }
    }
}
