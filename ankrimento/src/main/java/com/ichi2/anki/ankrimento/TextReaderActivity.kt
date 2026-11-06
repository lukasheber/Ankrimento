// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.ReadingSettingsSheet
import com.ichi2.anki.common.destinations.DeferredNavigation
import com.ichi2.anki.common.destinations.NoteEditorDestination
import com.ichi2.anki.common.destinations.toIntent
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * A topic without a document: its note, as its card template renders it,
 * read like a page of a book. There is nothing to reveal, so no "Show
 * answer"; the review bar ends it like any other topic. Extract is on the
 * text-selection menu, as in the document readers.
 *
 * Text size and page colour are this device's (desktop keeps no such setting
 * for an ordinary topic); typeface, spacing and margins are shared with the
 * EPUB reader.
 */
class TextReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private lateinit var preferences: ReadingPreferences
    private var textScale = 1.0
    private var appearance = "original"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        textScale = stored.getFloat(KEY_SCALE, 1f).toDouble()
        appearance = stored.getString(KEY_APPEARANCE, null) ?: "original"
        preferences = ReadingPreferences.load(this)
        webView =
            ReaderWebView(this).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Desktop's audio topic keeps where listening stopped in the page's local storage.
                settings.domStorageEnabled = true
            }
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), SELECTION_JS) { result ->
                    extract(decodeJsString(result))
                },
                // SuperMemo's way from reading to items: the selection hidden in its sentence.
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_cloze), CLOZE_JS, ::cloze),
            )
        setReaderContent(webView)
        webView.addJavascriptInterface(CardPage.Links(this), "AnkrimentoCard")
        toggleBarsOnTap(webView)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_reading_settings), IncrementoIcons.TextFormat, ::showReadingSettings),
                ReaderTool(getString(R.string.ankrimento_extract_selection)) {
                    webView.evaluateJavascript(SELECTION_JS) { extract(decodeJsString(it)) }
                },
                ReaderTool(getString(R.string.ankrimento_edit_topic), IncrementoIcons.Edit, ::editTopic),
            )
        lifecycleScope.launch { load() }
    }

    /** The topic's note in AnkiDroid's editor; the page shows the edit on return. */
    private var editing = false

    private fun editTopic() {
        editing = true
        startActivity(
            with(DeferredNavigation) { NoteEditorDestination.EditNoteFromPreviewer(cardId).toIntent() },
        )
    }

    override fun onResume() {
        super.onResume()
        if (editing) {
            editing = false
            lifecycleScope.launch { load() }
        }
    }

    private fun cloze(result: String) {
        val parts = runCatching { JSONObject(decodeJsString(result)) }.getOrNull()
        if (parts == null || parts.optString("selected").isBlank()) {
            toast(getString(R.string.ankrimento_web_select_first))
            return
        }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.extractCloze(
                        cardId,
                        parts.optString("before"),
                        parts.optString("selected"),
                        parts.optString("after"),
                    )
                }
            }.onFailure { error ->
                Timber.w(error, "Making a cloze failed")
                toast(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private suspend fun load() {
        val (html, mediaDir) =
            runCatching {
                withCol {
                    val output = getCard(cardId).renderOutput(this, reload = true)
                    CardPage.html(output.css, output.answerText) to media.dir
                }
            }.getOrElse { error ->
                Timber.w(error, "Rendering a topic failed")
                Toast.makeText(this, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        val media = mediaDir.canonicalFile
        val assetLoader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/media/") { path -> CardPage.mediaFile(media, path) }
                .addPathHandler("/_anki/", CardPage.ankiAssets(this))
                .build()
        webView.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = CardPage.mediaRequest(media, request) ?: assetLoader.shouldInterceptRequest(request.url)

                // Links in a topic open in the browser, not in place of the topic.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    openUrl(request.url.toString())
                    return true
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) = applyReading()
            }
        webView.loadDataWithBaseURL(CardPage.BASE_URL, html, "text/html", "utf-8", null)
    }

    private fun applyReading() {
        webView.evaluateJavascript(preferences.script(), null)
        webView.evaluateJavascript(CardPage.pageScript(textScale, appearance, alignStart = true), null)
    }

    private fun showReadingSettings() {
        sheet = {
            ReadingSettingsSheet(
                textScale = textScale,
                appearance = appearance,
                preferences = preferences,
                onTextScale = { scale ->
                    textScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
                    store()
                    showReadingSettings()
                },
                onAppearance = { mode ->
                    appearance = mode
                    store()
                    showReadingSettings()
                },
                onPreferences = { chosen ->
                    preferences = chosen
                    chosen.save(this)
                    applyReading()
                    showReadingSettings()
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun store() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
            putFloat(KEY_SCALE, textScale.toFloat())
            putString(KEY_APPEARANCE, appearance)
        }
        applyReading()
    }

    private fun extract(text: String) {
        lifecycleScope.launch {
            // Upstream's extraction from any card: the extract joins the topic in the knowledge tree.
            val started = withCol { Ankrimento.webExtract(cardId, text) }
            if (!started) Toast.makeText(this@TextReaderActivity, R.string.ankrimento_web_select_first, Toast.LENGTH_SHORT).show()
        }
    }

    /** evaluateJavascript returns a JSON-encoded string. */
    private fun decodeJsString(result: String): String = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")

    companion object {
        /**
         * The selection and its sentence: the text of the paragraph around it, from the end of the sentence
         * before (". ", "! ", "? ") to the end of its own; as JSON (before, selected, after).
         */
        private const val CLOZE_JS =
            "(function () { var s = window.getSelection(); if (!s || !s.rangeCount || s.isCollapsed) return null;" +
                " var r = s.getRangeAt(0); var node = r.startContainer; var block = node.nodeType === 1 ? node : node.parentElement;" +
                " block = (block && block.closest('p,li,dd,dt,blockquote,h1,h2,h3,h4,h5,h6,td,div')) || document.body;" +
                " var head = document.createRange(); head.selectNodeContents(block); head.setEnd(r.startContainer, r.startOffset);" +
                " var start = head.toString().length; var selected = r.toString(); var text = block.textContent || '';" +
                " var end = Math.min(text.length, start + selected.length);" +
                " var from = start; while (from > 1 && !/[.!?]\\s/.test(text.slice(from - 2, from))) from--; if (from <= 1) from = 0;" +
                " var stop = /[.!?](\\s|$)/g; stop.lastIndex = end; var found = stop.exec(text);" +
                " var to = found ? found.index + 1 : text.length;" +
                " return JSON.stringify({before: text.slice(from, start), selected: selected, after: text.slice(end, to)}); })()"

        private const val SELECTION_JS = "String(window.getSelection ? window.getSelection() : '')"

        /** Shared with the item reader, which shows cards in the same text size and page colour. */
        internal const val PREFERENCES = "ankrimento_text_reader"
        internal const val KEY_SCALE = "text_scale"
        internal const val KEY_APPEARANCE = "appearance"

        /** The EPUB reader's limits (upstream's). */
        private const val MIN_SCALE = 0.7
        private const val MAX_SCALE = 2.2

        fun intent(
            context: Context,
            cardId: Long,
        ) = ReaderActivity.intent(context, "text", cardId, session = false)!!
    }
}
