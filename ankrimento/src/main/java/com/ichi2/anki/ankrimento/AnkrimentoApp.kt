// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import com.ichi2.anki.libanki.Card
import java.io.Closeable

/**
 * What only the app can do for Ankrimento, registered by the app when it starts.
 * Restoring a full backup uses both, as desktop's restore makes an Anki safety
 * backup first and reopens the profile after.
 */
object AnkrimentoApp {
    /** AnkiDroid's own backup of the collection (listed under Restore from backup); true when made. */
    var safetyBackup: (suspend () -> Boolean)? = null

    /** Restarts the app once background work has finished and the collection is closed. */
    var restartApp: (suspend (Context) -> Unit)? = null

    /** AnkiDroid's own card display, for items in the learning screen; without it they open in the reviewer. */
    var cardDisplay: CardDisplay? = null

    /** A card as AnkiDroid's study screen renders and plays it. */
    interface CardDisplay {
        /** [card]'s question: its play buttons, video players and box to type the answer in. */
        suspend fun question(card: Card): String

        /** [card]'s answer, comparing [typed] with the answer expected when the card asks for one. */
        suspend fun answer(
            card: Card,
            typed: String,
        ): String

        /** How AnkiDroid's study screen dresses [card]'s page: its theme (night mode) and card classes. */
        fun look(
            context: Context,
            card: Card,
        ): PageLook

        /** AnkiDroid's player of a card's sound, video and text to speech; [evaluate] runs script in the card's page. */
        fun player(
            context: Context,
            evaluate: (String) -> Unit,
            onError: (String) -> Unit,
        ): CardPlayer
    }

    /** A card page's classes ([htmlClass] on `<html>`, [bodyClass] on `<body>`) and the theme's colours as [css]. */
    data class PageLook(
        val htmlClass: String,
        val bodyClass: String,
        val css: String,
    )

    interface CardPlayer : Closeable {
        /** Plays the shown side by itself when the deck's options say so. */
        fun autoplay(
            card: Card,
            answer: Boolean,
        )

        /** A link of the card's page: a play button, or a video ending; false for any other link. */
        fun onLink(
            card: Card,
            url: String,
        ): Boolean

        fun stop()
    }
}
