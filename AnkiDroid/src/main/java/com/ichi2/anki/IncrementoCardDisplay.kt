// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.content.Context
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.AnkrimentoApp
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.cardviewer.MediaErrorHandler
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.multimedia.getAvTag
import com.ichi2.anki.multimedia.replaceAvRefsWithPlayButtons
import com.ichi2.anki.previewer.TypeAnswer
import com.ichi2.anki.reviewer.CardSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Items in Incremento's learning screen, shown as AnkiDroid's study screen shows them
 * (`CardViewerViewModel`, `ReviewerViewModel`): its play buttons and video players, its box
 * for a typed answer and its media player, text to speech included.
 */
object IncrementoCardDisplay : AnkrimentoApp.CardDisplay {
    override suspend fun question(card: Card): String {
        val text = prepare(card, withCol { card.question(this) })
        return TypeAnswer.getInstance(card, text)?.questionFilter(text) ?: text
    }

    override suspend fun answer(
        card: Card,
        typed: String,
    ): String {
        val text = prepare(card, withCol { card.answer(this) })
        return TypeAnswer.getInstance(card, text)?.answerFilter(typed) ?: text
    }

    /** As `CardViewerViewModel.prepareCardTextForDisplay`. */
    private suspend fun prepare(
        card: Card,
        text: String,
    ) = replaceAvRefsWithPlayButtons(
        text = withCol { media.escapeMediaFilenames(text) },
        renderOutput = withCol { card.renderOutput(this) },
    )

    override fun player(
        context: Context,
        evaluate: (String) -> Unit,
        onError: (String) -> Unit,
    ): AnkrimentoApp.CardPlayer = Player(context, evaluate, onError)

    private class Player(
        context: Context,
        evaluate: (String) -> Unit,
        onError: (String) -> Unit,
    ) : AnkrimentoApp.CardPlayer {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val media =
            CardMediaPlayer(
                javascriptEvaluator = { evaluate(it) },
                mediaErrorListener =
                    MediaErrorHandler(
                        onMediaError = onError,
                        onTtsError = { onError(it.localizedErrorMessage(context)) },
                    ),
            )

        override fun autoplay(
            card: Card,
            answer: Boolean,
        ) {
            // As ReviewerViewModel.loadAndPlayMedia.
            scope.launch {
                media.loadCardAvTags(card)
                media.autoplayAllForSide(CardSide.fromAnswer(answer))
            }
        }

        override fun onLink(
            card: Card,
            url: String,
        ): Boolean {
            when {
                url.startsWith("playsound:") -> scope.launch { getAvTag(card, url)?.let { media.playOne(it) } }
                url.startsWith("videoended:") -> media.onVideoFinished()
                url.startsWith("videopause:") -> media.onVideoPaused()
                else -> return false
            }
            return true
        }

        override fun stop() {
            scope.launch { media.stop() }
        }

        override fun close() {
            media.close()
            scope.cancel()
        }
    }
}
