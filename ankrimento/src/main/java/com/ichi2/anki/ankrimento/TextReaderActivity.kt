// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.ReadingSettingsSheet
import com.ichi2.anki.ankrimento.ui.TopicEditorBar
import kotlinx.coroutines.launch
import org.json.JSONArray
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
        segmentOpened = savedInstanceState?.getBoolean(STATE_SEGMENT_OPENED) ?: false
        val stored = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        textScale = stored.getFloat(PREF_SCALE, 1f).toDouble()
        appearance = stored.getString(PREF_APPEARANCE, null) ?: "original"
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
                clozeAction(),
            )
        setReaderContent(webView)
        webView.addJavascriptInterface(CardPage.Links(this), "AnkrimentoCard")
        toggleBarsOnTap(webView)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_reading_settings), IncrementoIcons.TextFormat, onClick = ::showReadingSettings),
                ReaderTool(getString(R.string.ankrimento_extract_selection)) {
                    webView.evaluateJavascript(SELECTION_JS) { extract(decodeJsString(it)) }
                },
                ReaderTool(getString(R.string.ankrimento_edit_topic), IncrementoIcons.Edit, onClick = ::startEditing),
                ReaderTool(getString(R.string.ankrimento_edit_all_fields), onClick = ::editNote),
            )
        lifecycleScope.launch { load() }
    }

    override fun onNoteEdited() {
        lifecycleScope.launch { load() }
    }

    // ── Editing where it is read ────────────────────────────────────────────

    private var editing = false
    private var editedFrom = ""
    private var readingTools: List<ReaderTool> = emptyList()
    private var readingSelection: List<ReaderWebView.SelectionAction> = emptyList()

    /** Makes the topic's text editable in place, with formatting tools; the source link is a chip that stays. */
    private fun startEditing() {
        if (editing) return
        editing = true
        readingTools = tools
        readingSelection = webView.selectionActions
        webView.selectionActions = emptyList()
        webView.onTap = null
        tools = listOf(ReaderTool(getString(R.string.ankrimento_edit_save), IncrementoIcons.Check, onClick = ::saveEditing))
        backIntercept = {
            if (editing) cancelEditing()
            editing
        }
        editBar = {
            TopicEditorBar { command ->
                webView.evaluateJavascript(command, null)
                webView.requestFocus()
            }
        }
        lifecycleScope.launch { load() }
    }

    private fun stopEditing() {
        editing = false
        editBar = null
        backIntercept = null
        tools = readingTools
        webView.selectionActions = readingSelection
        toggleBarsOnTap(webView)
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(webView.windowToken, 0)
        lifecycleScope.launch { load() }
    }

    private fun editorHtml(then: (String) -> Unit) {
        webView.evaluateJavascript("document.getElementById('ankrimento-editor').innerHTML") { then(decodeJsString(it)) }
    }

    private fun saveEditing() {
        editorHtml { html ->
            lifecycleScope.launch {
                runCatching { withCol { Ankrimento.topicSave(cardId, html) } }
                    .onSuccess { stopEditing() }
                    .onFailure { error ->
                        Timber.w(error, "Saving the topic failed")
                        toast(error.message ?: error.javaClass.simpleName)
                    }
            }
        }
    }

    /** Back while editing: leaves at once when nothing changed, otherwise asks. */
    private fun cancelEditing() {
        editorHtml { html ->
            if (html == editedFrom) {
                stopEditing()
            } else {
                sheet = {
                    AlertDialog(
                        onDismissRequest = { sheet = null },
                        title = { Text(stringResource(R.string.ankrimento_edit_discard_title)) },
                        confirmButton = {
                            TextButton(onClick = {
                                sheet = null
                                stopEditing()
                            }) { Text(stringResource(R.string.ankrimento_edit_discard)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { sheet = null }) { Text(stringResource(R.string.ankrimento_edit_keep_editing)) }
                        },
                    )
                }
            }
        }
    }

    private suspend fun load() {
        val (html, mediaDir) =
            runCatching {
                withCol {
                    val card = getCard(cardId)
                    val output = card.renderOutput(this, reload = true)
                    // In AnkiDroid's look (night mode); the page colour setting still overrides it.
                    val look = AnkrimentoApp.cardDisplay?.look(this@TextReaderActivity, card)
                    if (editing) {
                        val body = "<div id=\"ankrimento-editor\" contenteditable=\"true\">${Ankrimento.topicEditable(cardId)}</div>"
                        CardPage.html(output.css + EDITOR_CSS, body, look = look) to media.dir
                    } else {
                        CardPage.html(output.css, output.answerText, look = look) to media.dir
                    }
                }
            }.getOrElse { error ->
                Timber.w(error, "Rendering a topic failed")
                showToast(error.message ?: error.javaClass.simpleName)
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
                ) {
                    applyReading()
                    if (editing) beginTyping() else openVideoSegment()
                }
            }
        webView.loadDataWithBaseURL(CardPage.BASE_URL, html, "text/html", "utf-8", null)
    }

    /** The editor takes the cursor at its end, with the keyboard up, and remembers the text it started with. */
    private fun beginTyping() {
        webView.requestFocus()
        webView.evaluateJavascript(
            "(function () { var e = document.getElementById('ankrimento-editor'); e.focus();" +
                " var r = document.createRange(); r.selectNodeContents(e); r.collapse(false);" +
                " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r); return e.innerHTML; })()",
        ) { editedFrom = decodeJsString(it) }
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.showSoftInput(webView, 0)
    }

    override fun turnPage(forward: Boolean): Boolean {
        if (editing) return false
        webView.evaluateJavascript(scrollScreenJs(forward), null)
        return true
    }

    // -- A video extract in a session plays by itself --

    private var segmentOpened = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SEGMENT_OPENED, segmentOpened)
    }

    /**
     * A topic that is a video extract (a link to a moment or a segment, with little else) plays it when it comes up in
     * a session, once; Back from the player returns here to grade the topic. Opened from the library or the tree it
     * just shows the link.
     */
    private fun openVideoSegment() {
        if (segmentOpened || editing || !inSession) return
        webView.evaluateJavascript(VIDEO_LINK_JS) { result ->
            val message = decodeJsString(result)
            if (!message.startsWith("incremento_open_video:") || segmentOpened) return@evaluateJavascript
            SourceLinks.intentFor(this, message)?.let {
                segmentOpened = true
                startActivity(it)
            }
        }
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
            putFloat(PREF_SCALE, textScale.toFloat())
            putString(PREF_APPEARANCE, appearance)
        }
        applyReading()
    }

    private fun extract(text: String) {
        lifecycleScope.launch {
            // Upstream's extraction from any card: the extract joins the topic in the knowledge tree.
            val started = withCol { Ankrimento.webExtract(cardId, text) }
            if (!started) this@TextReaderActivity.showToast(R.string.ankrimento_web_select_first, long = false)
        }
    }

    /** evaluateJavascript returns a JSON-encoded string. */
    private fun decodeJsString(result: String): String = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")

    companion object {
        /** The editable page: room to write, a visible chip for the source link, no outline around the text. */
        private const val EDITOR_CSS =
            "#ankrimento-editor { outline: none; min-height: 60vh; padding-bottom: 40vh; caret-color: currentColor; }" +
                " .ankrimento-source { display: inline-block; padding: 2px 10px; margin: 2px 0; border-radius: 12px;" +
                " background: rgba(127,127,127,.2); font-size: .85em; user-select: none; -webkit-user-select: none; }"

        private const val STATE_SEGMENT_OPENED = "segment_opened"

        /** The `incremento_open_video:…` command of the topic's video link, when the topic is little more than that link. */
        private const val VIDEO_LINK_JS =
            "(function () { var a = document.querySelector('a[onclick*=\"incremento_open_video:\"]'); if (!a) return null;" +
                " var rest = (document.body.innerText || '').length - (a.innerText || '').length; if (rest > 120) return null;" +
                " var m = (a.getAttribute('onclick') || '').match(/incremento_open_video:[0-9.:]+/);" +
                " return m ? m[0] : null; })()"

        private const val SELECTION_JS = "String(window.getSelection ? window.getSelection() : '')"

        /** Shared with the item reader, which shows cards in the same text size and page colour. */
        internal const val PREFERENCES = "ankrimento_text_reader"
        internal const val PREF_SCALE = "text_scale"
        internal const val PREF_APPEARANCE = "appearance"

        /** The EPUB reader's limits (upstream's). */
        private const val MIN_SCALE = 0.7
        private const val MAX_SCALE = 2.2

        fun intent(
            context: Context,
            cardId: Long,
        ) = ReaderActivity.intent(context, "text", cardId, session = false)!!
    }
}
