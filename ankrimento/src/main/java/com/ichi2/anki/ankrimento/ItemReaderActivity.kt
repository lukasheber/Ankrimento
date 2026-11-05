// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.utils.ext.hideAudioPlayButtons
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * An item (a flashcard) in a session, on the same screen as topics: the
 * question, Show answer, then the answer and the grades, Anki's four or
 * Incremento's Fail / Pass when it is set to use them. Answering moves on
 * as topics do, and Back returns to the previous element.
 *
 * Sound and video play as in the reviewer, by themselves and from their buttons, and an
 * answer to type is compared when the answer is shown. Only cards it shows as the reviewer
 * would come here ([CardPage.canShow]).
 */
class ItemReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var question = ""
    private var answer = ""
    private var css = ""
    private var answerText = ""
    private var answerMedia: CardPage.SideMedia? = null
    private var typed: CardPage.TypedAnswer? = null

    override val answerAlreadyShown = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            ReaderWebView(this).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // A card's sound plays by itself, as in the reviewer.
                settings.mediaPlaybackRequiresUserGesture = false
            }
        setReaderContent(webView)
        webView.addJavascriptInterface(CardPage.Links(this), "AnkrimentoCard")
        webView.addJavascriptInterface(TypedBox(), "AnkrimentoItem")
        // Desktop's extract from the reviewer card: the item is the extract's source.
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), SELECTION_JS) { result ->
                    val text = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrDefault("")
                    lifecycleScope.launch {
                        if (!withCol { Ankrimento.webExtract(cardId, text) }) {
                            Toast.makeText(this@ItemReaderActivity, R.string.ankrimento_web_select_first, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val media =
            runCatching {
                withCol {
                    val output = getCard(cardId).renderOutput(this, reload = true)
                    val card = getCard(cardId)

                    fun sideMedia(side: String) =
                        CardPage.SideMedia.of(
                            output.questionAvTags,
                            output.answerAvTags,
                            side,
                            card.autoplay(this),
                            card.replayQuestionAudioOnAnswerSide(this),
                            !config.hideAudioPlayButtons,
                        )
                    css = output.css
                    answerText = output.answerText
                    answerMedia = sideMedia("a")
                    typed = CardPage.TypedAnswer.of(this, card, output.questionText)
                    question = CardPage.html(css, typed?.onQuestion(output.questionText) ?: output.questionText, sideMedia("q"))
                    answer = answerPage(this, "")
                    media.dir
                }
            }.getOrElse { error ->
                Timber.w(error, "Rendering an item failed")
                Toast.makeText(this, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
                finish()
                return
            }.canonicalFile
        webView.webViewClient = client(media)
        if (!inSession) {
            // Opened to look at it (from the knowledge tree): the whole card, nothing to reveal.
            webView.loadDataWithBaseURL(CardPage.BASE_URL, answer, "text/html", "utf-8", null)
            return
        }
        webView.loadDataWithBaseURL(CardPage.BASE_URL, question, "text/html", "utf-8", null)
        revealAction = ::reveal
    }

    private fun client(media: File): WebViewClientCompat {
        val assetLoader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/media/") { path -> CardPage.mediaFile(media, path) }
                .addPathHandler("/_anki/", CardPage.ankiAssets(this))
                .build()
        return object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(
                view: WebView,
                url: String,
            ) {
                // The text reader's size and page colour; the card keeps its template's alignment.
                val stored = getSharedPreferences(TextReaderActivity.PREFERENCES, MODE_PRIVATE)
                val scale = stored.getFloat(TextReaderActivity.KEY_SCALE, 1f).toDouble()
                val appearance = stored.getString(TextReaderActivity.KEY_APPEARANCE, null) ?: "original"
                view.evaluateJavascript(CardPage.pageScript(scale, appearance, alignStart = false), null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                openUrl(request.url.toString())
                return true
            }
        }
    }

    /** The answer side; a typed answer is compared with what the card expects, by Anki's backend. */
    private fun answerPage(
        col: Collection,
        provided: String,
    ): String {
        val body =
            typed?.let { it.onAnswer(answerText, col.compareAnswer(it.expected, provided, it.combining)) }
                ?: answerText
        return CardPage.html(css, body, answerMedia)
    }

    /** Enter in the box to type in shows the answer, as the screen's button does. */
    private inner class TypedBox {
        @android.webkit.JavascriptInterface
        fun submit() {
            runOnUiThread { revealAction?.invoke() }
        }
    }

    override fun onPause() {
        // Sound stops with the screen: the next element may be on its way.
        webView.evaluateJavascript("if (window.ankrimentoStop) ankrimentoStop();", null)
        super.onPause()
    }

    private fun reveal() {
        revealAction = null
        if (typed == null) return showAnswer()
        webView.evaluateJavascript(
            "(function () { var box = document.getElementById('typeans'); return box ? box.value : ''; })()",
        ) { result ->
            val provided = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrDefault("")
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(webView.windowToken, 0)
            lifecycleScope.launch {
                answer = withCol { answerPage(this, provided) }
                showAnswer()
            }
        }
    }

    private fun showAnswer() {
        webView.loadDataWithBaseURL(CardPage.BASE_URL, answer, "text/html", "utf-8", null)
        // Desktop times a review from the question to the answer.
        lifecycleScope.launch { withCol { Ankrimento.answerShown(cardId) } }
    }

    /** Incremento's Fail / Pass when enabled, otherwise Anki's grades; each with Anki's next interval. */
    override fun answerButtonsFor(col: Collection): List<Ankrimento.AnswerButton>? {
        val state = col.sched.currentQueueState()?.takeIf { it.topCard.id == cardId }
        val intervals = state?.let { col.sched.describeNextStates(it.states) }
        Ankrimento.answerButtons(cardId)?.let { buttons ->
            return buttons.map { it.copy(due = it.due ?: intervals?.getOrNull(it.ease - 1)) }
        }
        val names =
            listOf(
                R.string.ankrimento_grade_again,
                R.string.ankrimento_grade_hard,
                R.string.ankrimento_grade_good,
                R.string.ankrimento_grade_easy,
            )
        return names.mapIndexed { index, name ->
            Ankrimento.AnswerButton(Rating.forNumber(index), getString(name), ease = index + 1, due = intervals?.getOrNull(index))
        }
    }

    private companion object {
        const val SELECTION_JS = "String(window.getSelection ? window.getSelection() : '')"
    }
}
