// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDeckTest : RobolectricTest() {
    @Test
    fun `cards that cannot be studied now are not left in the session`() {
        val first = addBasicNote("first").firstCard()
        addBasicNote("second")
        addDynamicDeck(SESSION, search = "deck:Default")
        assertThat(SessionDeck.remaining(col, SESSION).second, equalTo(2))

        // A postponed topic is buried: it stays in the filtered deck, but the session is over without it.
        col.sched.buryCards(listOf(first.id))
        assertThat(SessionDeck.remaining(col, SESSION).second, equalTo(1))
    }

    @Test
    fun `no session deck means nothing left`() {
        assertThat(SessionDeck.remaining(col, SESSION), equalTo(null to 0))
    }

    @Test
    fun `every preset's session deck is a session deck, other decks are not`() {
        // Upstream's is_incremento_session_deck_name: one deck per session preset.
        assertThat(SessionDeck.isSessionDeckName("Incremento Session"), equalTo(true))
        assertThat(SessionDeck.isSessionDeckName("Incremento Session (Evening)"), equalTo(true))
        assertThat(SessionDeck.isSessionDeckName("Incremento Session ()"), equalTo(false))
        assertThat(SessionDeck.isSessionDeckName("Incremento Sessions"), equalTo(false))
        assertThat(SessionDeck.isSessionDeckName("Topics"), equalTo(false))
    }

    private companion object {
        const val SESSION = "Incremento Session"
    }
}
