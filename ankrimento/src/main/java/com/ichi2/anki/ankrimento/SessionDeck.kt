// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.annotation.WorkerThread
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId

/** The filtered deck an Incremento session fills. */
object SessionDeck {
    private const val BASE_NAME = "Incremento Session"
    private val REVIEW_DECK_NAMES = setOf("Incremento PDF Review", "Incremento EPUB Review")

    /**
     * Whether [name] is one of Incremento's session decks: the base deck or a preset's
     * `Incremento Session (<preset>)`, as upstream's `is_incremento_session_deck_name`.
     */
    fun isSessionDeckName(name: String): Boolean {
        if (name == BASE_NAME) return true
        val prefix = "$BASE_NAME ("
        return name.startsWith(prefix) && name.endsWith(")") && name.length > prefix.length + 1
    }

    /**
     * Whether [name] is desktop's review deck for a document (`INCREMENTO_PDF_REVIEW_DECK`,
     * `INCREMENTO_EPUB_REVIEW_DECK`) with nothing to study. The phone empties these when a review
     * ends. One that arrives from desktop with cards shows whenever they are due, so they can be reached.
     */
    fun isHiddenReviewDeck(
        name: String,
        dueCards: Int,
    ): Boolean = dueCards == 0 && name in REVIEW_DECK_NAMES

    /** The deck named [name] and how many of its cards can be studied now (0 when it does not exist). */
    @WorkerThread
    fun remaining(
        col: Collection,
        name: String,
    ): Pair<DeckId?, Int> {
        val deckId = col.decks.idForName(name) ?: return null to 0
        // Not the cards in the deck: buried (postponed) ones and learning cards due later stay in it.
        val counts = col.sched.deckDueTree().find(deckId) ?: return deckId to 0
        return deckId to counts.newCount + counts.lrnCount + counts.revCount
    }
}
