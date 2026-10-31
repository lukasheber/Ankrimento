// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import anki.collection.OpChanges
import anki.collection.OpChangesAfterUndo
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.undoAvailable
import com.ichi2.anki.observability.undoableOp

/**
 * SuperMemo's learning loop for a session's topics: a reader answers its
 * topic and moves straight on to the next element, and Back returns to the
 * previous one, without leaving the reading screen in between.
 *
 * Answers go through Anki's scheduler exactly as the reviewer's do, with
 * Incremento's rating remap and post-answer scheduling around them. Back is
 * Anki's undo of that move, so Incremento's topic state follows it as it
 * follows any undo; it is offered only while nothing else has changed since
 * the move, so it never takes back an extract made afterwards.
 */
internal object LearnLoop {
    /** Where a move leads. */
    sealed interface Step

    /** The element to show next: a card, and its Incremento reader ("" for the reviewer's card view). */
    data class Next(
        val cardId: Long,
        val reader: String,
    ) : Step

    /** No more cards in the session. */
    data object End : Step

    /** The card is not the scheduler's current one: the reviewer, which knows why, records the answer. */
    data object NotCurrent : Step

    /**
     * Anki's undo-step counter right after the last move. While it is still the
     * counter, nothing has changed since, and one undo takes the move back:
     * an answer and each topic action are one undo step. (Undo is a step of its
     * own too: the counter never goes back down.)
     */
    private var lastMoveStep: Int? = null

    /** Answers [cardId] with the answer button on [rating]'s slot, and leads to the next element. */
    suspend fun answer(
        handler: Any,
        cardId: Long,
        rating: Rating,
    ): Step {
        val state = withCol { sched.currentQueueState() }
        if (state == null || state.topCard.id != cardId) return NotCurrent
        // Desktop times a review from the question to the answer.
        withCol { Ankrimento.answerShown(cardId) }
        val submitted = withCol { Ankrimento.ratingToSubmit(cardId, rating) }
        move(handler) { sched.answerCard(state, submitted) }
        withCol { Ankrimento.onCardAnswered(cardId, submitted) }
        return next()
    }

    /** After a topic action rescheduled [action]'s card: the next element. */
    suspend fun afterAction(action: suspend () -> Unit): Step {
        action()
        lastMoveStep = withCol { undoStatus().lastStep }
        return next()
    }

    /** Whether Back can return to the element before this one. */
    suspend fun canGoBack(): Boolean =
        withCol {
            val status = undoStatus()
            // Otherwise an answer the reviewer recorded (an item), or an earlier one that Back reaches next.
            if (status.lastStep == lastMoveStep) status.undo != null else status.undo == tr.actionsAnswerCard()
        }

    /** Takes back the last move; returns the element it returns to, or null when Back is not available. */
    suspend fun back(handler: Any): Step? {
        if (!canGoBack()) return null
        lastMoveStep = null
        undoableOp(handler) { if (undoAvailable()) undo() else OpChangesAfterUndo.getDefaultInstance() }
        return next()
    }

    private suspend fun move(
        handler: Any,
        op: com.ichi2.anki.libanki.Collection.() -> OpChanges,
    ) {
        undoableOp(handler, op)
        lastMoveStep = withCol { undoStatus().lastStep }
    }

    /** The scheduler's next card and its reader. */
    private suspend fun next(): Step =
        withCol {
            val card = sched.currentQueueState()?.topCard ?: return@withCol End
            Next(card.id, Ankrimento.cardReader(card.id))
        }
}
