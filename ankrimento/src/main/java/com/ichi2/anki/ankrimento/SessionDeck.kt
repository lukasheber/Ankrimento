// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.annotation.WorkerThread
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId

/** The filtered deck an Incremento session fills. */
object SessionDeck {
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
