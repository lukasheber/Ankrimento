// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.view.View
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.reviewer.AnswerButtons.Companion.getBackgroundColors
import com.ichi2.anki.reviewer.AnswerButtons.Companion.getTextColors

/**
 * Tells Incremento a card's question is on screen (it times reviews from
 * here) and, like desktop's reader docks, opens the card's document.
 */
internal fun AbstractFlashcardViewer.notifyIncrementoQuestionShown(cardId: CardId) {
    if (!Ankrimento.isStarted) return
    launchCatchingTask {
        val reader =
            withCol {
                Ankrimento.questionShown(cardId)
                Ankrimento.cardReader(cardId)
            }
        // Topic actions depend on the card just shown.
        invalidateOptionsMenu()
        when (reader) {
            "pdf" -> startActivity(PdfReaderActivity.intent(this@notifyIncrementoQuestionShown, cardId))
            "epub" -> startActivity(EpubReaderActivity.intent(this@notifyIncrementoQuestionShown, cardId))
            "web" -> startActivity(WebReaderActivity.intent(this@notifyIncrementoQuestionShown, cardId))
            "video" -> startActivity(VideoReaderActivity.intent(this@notifyIncrementoQuestionShown, cardId))
            "writing" -> startActivity(WritingActivity.intent(this@notifyIncrementoQuestionShown, cardId))
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
        } ?: return
    val background = getBackgroundColors(this)
    val textColors = getTextColors(this)
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
