// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.ThemeUtils
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.AnkrimentoApp
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.cardviewer.MediaErrorHandler
import com.ichi2.anki.common.utils.android.systemIsInNightMode
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.multimedia.getAvTag
import com.ichi2.anki.multimedia.replaceAvRefsWithPlayButtons
import com.ichi2.anki.previewer.TypeAnswer
import com.ichi2.anki.previewer.bodyClassForCardOrd
import com.ichi2.anki.reviewer.CardSide
import com.ichi2.anki.settings.PrefsRepository
import com.ichi2.anki.settings.enums.AppTheme
import com.ichi2.utils.toRGBHex
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

    /** As `stdHtml` and `CardViewerViewModel.bodyClass`. */
    override fun look(
        context: Context,
        card: Card,
    ): AnkrimentoApp.PageLook {
        // As Themes.updateCurrentTheme, without its side effects: Themes.currentTheme is only set once one
        // of AnkiDroid's own screens starts, and a reader can be the first screen shown.
        val prefs = PrefsRepository(context)
        val appTheme = prefs.appTheme
        val nightMode = (appTheme == AppTheme.FOLLOW_SYSTEM && systemIsInNightMode(context)) || appTheme == AppTheme.NIGHT
        val themed = ContextThemeWrapper(context, (if (nightMode) prefs.nightTheme else prefs.dayTheme).styleResId)
        val canvas = ThemeUtils.getThemeAttrColor(themed, android.R.attr.colorBackground).toRGBHex()
        val fg = ThemeUtils.getThemeAttrColor(themed, android.R.attr.textColor).toRGBHex()
        val root = if (nightMode) ":root[class*=night-mode]" else ":root"
        return AnkrimentoApp.PageLook(
            htmlClass = if (nightMode) "night-mode" else "",
            bodyClass = bodyClassForCardOrd(card.ord, nightMode),
            css = "$root { --canvas: $canvas; --fg: $fg; } .night-mode button { --canvas: #606060; --fg: #eee; }",
        )
    }

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
