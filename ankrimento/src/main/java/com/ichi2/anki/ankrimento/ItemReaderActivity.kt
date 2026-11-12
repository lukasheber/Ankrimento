// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.libanki.Card
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
 * The card is AnkiDroid's own rendering and playback ([AnkrimentoApp.cardDisplay]):
 * its play buttons and video players, its box for a typed answer, its player
 * of sound and text to speech, as in its study screen.
 */
class ItemReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var question = ""
    private var answer = ""

    /** AnkiDroid's look for the card (night mode, card classes). */
    private var look: AnkrimentoApp.PageLook? = null
    private var card: Card? = null
    private var player: AnkrimentoApp.CardPlayer? = null

    /** Whether the question asks for a typed answer, compared when the answer is shown. */
    private var asksTypedAnswer = false

    /** The side to play by itself once its page has loaded (true: the answer); null once played. */
    private var pendingAutoplay: Boolean? = null

    override val answerAlreadyShown = true

    override fun sessionTitle() = getString(R.string.ankrimento_extract_item)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            ReaderWebView(this).apply {
                settings.javaScriptEnabled = true
                // AnkiDroid's video players point at the media folder's files, as in its study screen.
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                // A card's sound and video play by themselves, as in the study screen.
                settings.mediaPlaybackRequiresUserGesture = false
            }
        setReaderContent(webView)
        webView.addJavascriptInterface(CardPage.Links(this), "AnkrimentoCard")
        // Desktop's extract from the reviewer card: the item is the extract's source.
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), SELECTION_JS) { result ->
                    val text = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrDefault("")
                    lifecycleScope.launch {
                        if (!withCol { Ankrimento.webExtract(cardId, text) }) {
                            this@ItemReaderActivity.showToast(R.string.ankrimento_web_select_first, long = false)
                        }
                    }
                },
            )
        // A tap on the question shows the answer, as a tap on a flashcard does; a typed answer needs its Show answer.
        webView.onTap = { if (!asksTypedAnswer) revealAction?.invoke() }
        // Fix a typo or reword the item where it is studied, as AnkiDroid's study screen allows.
        tools = listOf(ReaderTool(getString(R.string.ankrimento_edit_card), IncrementoIcons.Edit, onClick = ::editNote))
        lifecycleScope.launch { load() }
    }

    override fun onNoteEdited() {
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val display = AnkrimentoApp.cardDisplay
        val media =
            runCatching {
                checkNotNull(display) { "No card display" }
                val shown = withCol { getCard(cardId) }
                val questionBody = display.question(shown)
                asksTypedAnswer = TYPED_BOX in questionBody
                look = display.look(this@ItemReaderActivity, shown)
                question = CardPage.html("", questionBody, TYPED_ENTER, look)
                answer = CardPage.html("", display.answer(shown, ""), look = look)
                card = shown
                withCol { media.dir }
            }.getOrElse { error ->
                Timber.w(error, "Rendering an item failed")
                showToast(error.message ?: error.javaClass.simpleName)
                finish()
                return
            }.canonicalFile
        player =
            display!!.player(
                this,
                evaluate = { script -> runOnUiThread { webView.evaluateJavascript(script, null) } },
                onError = { message -> runOnUiThread { showToast(message) } },
            )
        webView.webViewClient = client(media)
        if (!inSession) {
            // Opened to look at it (from the knowledge tree): the whole card, nothing to reveal.
            show(answer, answerSide = true)
            return
        }
        show(question, answerSide = false)
        revealAction = ::reveal
    }

    private fun show(
        page: String,
        answerSide: Boolean,
    ) {
        pendingAutoplay = answerSide
        webView.loadDataWithBaseURL(CardPage.BASE_URL, page, "text/html", "utf-8", null)
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
            ): WebResourceResponse? = CardPage.mediaRequest(media, request) ?: assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(
                view: WebView,
                url: String,
            ) {
                // The text reader's size and page colour; the card keeps its template's alignment.
                val stored = getSharedPreferences(TextReaderActivity.PREFERENCES, MODE_PRIVATE)
                val scale = stored.getFloat(TextReaderActivity.PREF_SCALE, 1f).toDouble()
                val appearance = stored.getString(TextReaderActivity.PREF_APPEARANCE, null) ?: "original"
                view.evaluateJavascript(CardPage.pageScript(scale, appearance, alignStart = false, comfort = true), null)
                val side = pendingAutoplay ?: return
                pendingAutoplay = null
                card?.let { player?.autoplay(it, side) }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                when {
                    url == SHOW_ANSWER -> revealAction?.invoke()
                    card?.let { player?.onLink(it, url) } == true -> Unit
                    else -> openUrl(url)
                }
                return true
            }
        }
    }

    private fun reveal() {
        revealAction = null
        val shown = card
        if (!asksTypedAnswer || shown == null) return showAnswer()
        webView.evaluateJavascript(TYPED_VALUE_JS) { result ->
            val typed = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrDefault("")
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(webView.windowToken, 0)
            lifecycleScope.launch {
                answer =
                    runCatching { CardPage.html("", AnkrimentoApp.cardDisplay!!.answer(shown, typed), look = look) }.getOrDefault(answer)
                showAnswer()
            }
        }
    }

    private fun showAnswer() {
        show(answer, answerSide = true)
        // Desktop times a review from the question to the answer.
        lifecycleScope.launch { withCol { Ankrimento.answerShown(cardId) } }
    }

    override fun onStop() {
        // Sound stops with the screen, as in the study screen: the next element may be on its way.
        player?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        player?.close()
        super.onDestroy()
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

        const val TYPED_BOX = "id=\"typeans\""

        const val TYPED_VALUE_JS = "(function () { var box = document.getElementById('typeans'); return box ? box.value : ''; })()"

        /** What AnkiDroid's study screen asks when Enter is pressed in the box (`ankidroid-reviewer.js`). */
        const val SHOW_ANSWER = "ankidroid://show-answer"

        /** Its Enter handler, from `ankidroid-reviewer.js`, which the study screen's page loads. */
        const val TYPED_ENTER =
            "<script>globalThis.ankidroid = globalThis.ankidroid || {};" +
                " ankidroid.onTypeAnswerKeyDown = function (event) {" +
                " if (event.key === 'Enter') window.location.href = '$SHOW_ANSWER'; };</script>"
    }
}
