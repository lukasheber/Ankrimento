// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.ContentsSheet
import com.ichi2.anki.ankrimento.ui.FindBar
import com.ichi2.anki.ankrimento.ui.FindHit
import com.ichi2.anki.ankrimento.ui.FindSheet
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.LinkBackChip
import com.ichi2.anki.ankrimento.ui.MarkHighlight
import com.ichi2.anki.ankrimento.ui.MarksSheet
import com.ichi2.anki.ankrimento.ui.NoteSheet
import com.ichi2.anki.ankrimento.ui.ReaderProgress
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.ReadingSettingsSheet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Incremento's EPUB reader: each extracted chapter in a WebView with desktop's
 * EPUB page script, whose console messages Incremento's backend handles.
 * The top bar and the text-selection menu call the page script's own
 * actions, as desktop's toolbar does.
 *
 * Reading is set up as in an ebook app: "Aa" holds text size and page colour
 * (the book's settings, shared with desktop) and this device's typography;
 * the contents list jumps between chapters; a tap on the page leaves only
 * the text; the strip under it shows how far into the book the reader is.
 */
class EpubReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var section: Ankrimento.EpubSection? = null
    private var pendingScrollRatio: Double? = null

    /** Words for the next chapter's page script to find once loaded (a find or Search ALL result). */
    private var pendingSearch = ""
    private var findQuery = ""
    private var findHits = emptyList<Ankrimento.EpubSearchHit>()
    private var findIndex = -1
    private var findStatus: String? = null

    /** The anchor the next chapter's page script opens at (a followed link). */
    private var pendingFragment = ""

    /** Where each followed link was tapped (chapter, scroll ratio), most recent last; Back returns there. */
    private val linkBack = ArrayDeque<Pair<Int, Double>>()
    private val backFromLink =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = returnFromLink()
        }
    private var reading: Ankrimento.EpubReading? = null
    private lateinit var preferences: ReadingPreferences

    /** Page-script messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            ReaderWebView(this).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            val prefix = section?.let { "$MESSAGE_PREFIX${it.nonce}:" } ?: return false
                            val text = message.message()
                            if (!text.startsWith(prefix)) return false
                            messages.trySend(text.removePrefix(prefix))
                            return true
                        }
                    }
            }
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), EXTRACT_JS),
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_highlight), HIGHLIGHT_JS),
            )
        setReaderContent(webView)
        toggleBarsOnTap(webView)
        onBackPressedDispatcher.addCallback(this, backFromLink)
        webView.addJavascriptInterface(PageTurns(), "AnkrimentoPages")
        preferences = ReadingPreferences.load(this)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_reading_settings), IncrementoIcons.TextFormat, ::showReadingSettings),
                ReaderTool(getString(R.string.ankrimento_contents), IncrementoIcons.Contents, ::showContents),
                ReaderTool(getString(R.string.ankrimento_epub_find), IncrementoIcons.Search, onClick = ::showFind),
                ReaderTool(getString(R.string.ankrimento_marks_highlights), IncrementoIcons.Bookmark) { changeHighlights { "" } },
                ReaderTool(getString(R.string.ankrimento_epub_read_here)) {
                    webView.evaluateJavascript(READ_MARKER_JS) { placed ->
                        if (placed == "true") toast(getString(R.string.ankrimento_marked_read))
                    }
                },
                ReaderTool(getString(R.string.ankrimento_epub_previous)) { turnChapter(-1) },
                ReaderTool(getString(R.string.ankrimento_epub_next)) { turnChapter(1) },
                ReaderTool(getString(R.string.ankrimento_extract_selection)) { runScript(EXTRACT_JS) },
                ReaderTool(getString(R.string.ankrimento_highlight_selection)) { runScript(HIGHLIGHT_JS) },
            )
        lifecycleScope.launch {
            reading =
                runCatching { withCol { Ankrimento.epubReading(cardId) } }
                    .onFailure { Timber.w(it, "Loading the book's reading settings failed") }
                    .getOrNull()
            // A section asked for (Search ALL) from its start; otherwise where reading stopped.
            val start = intent.getIntExtra(EXTRA_START, -1).takeIf { it >= 0 }
            if (start != null) pendingSearch = intent.getStringExtra(EXTRA_FIND).orEmpty()
            load(start, start?.let { 0.0 })
        }
        Ankrimento.readerScope.launch {
            for (message in messages) {
                val current = section ?: continue
                if (!isDestroyed) showProgress(current, message)
                runCatching { withCol { Ankrimento.epubMessage(cardId, current.index, message) } }
                    .onSuccess { action -> if (!isDestroyed) action?.let { act(current, it) } }
                    .onFailure { Timber.w(it, "EPUB page message failed: %s", message.substringBefore(':')) }
            }
        }
    }

    private suspend fun act(
        current: Ankrimento.EpubSection,
        action: Ankrimento.EpubAction,
    ) {
        when (action) {
            is Ankrimento.EpubAction.Navigate -> load(action.section, action.scrollRatio)
            is Ankrimento.EpubAction.OpenWeb -> runOnUiThread { openUrl(action.url) }
            is Ankrimento.EpubAction.Blocked -> runOnUiThread { toast(action.message) }
            is Ankrimento.EpubAction.FollowLink ->
                runOnUiThread {
                    if (action.note.isEmpty()) {
                        followLink(current, action)
                    } else {
                        // A footnote is read over the page, as in an ebook app; Go to note follows the link.
                        sheet = {
                            NoteSheet(
                                text = action.note,
                                onGoToNote = {
                                    sheet = null
                                    followLink(current, action)
                                },
                                onDismiss = { sheet = null },
                            )
                        }
                    }
                }
        }
    }

    /** Opens the link's chapter at its anchor, as desktop does, keeping where it was tapped for Back. */
    private fun followLink(
        from: Ankrimento.EpubSection,
        link: Ankrimento.EpubAction.FollowLink,
    ) {
        linkBack.addLast(from.index to link.fromRatio)
        showLinkBack()
        if (link.section == section?.index) {
            runScript("window.incrementoOpenEpubAnchor && window.incrementoOpenEpubAnchor(${JSONObject.quote(link.fragment)});")
        } else {
            pendingFragment = link.fragment
            lifecycleScope.launch { load(link.section, 0.0) }
        }
    }

    private fun returnFromLink() {
        val (index, ratio) = linkBack.removeLastOrNull() ?: return
        showLinkBack()
        lifecycleScope.launch { load(index, ratio) }
    }

    private fun showLinkBack() {
        backFromLink.isEnabled = linkBack.isNotEmpty()
        overlay =
            if (linkBack.isEmpty()) {
                null
            } else {
                { LinkBackChip(onBack = ::returnFromLink, modifier = Modifier.align(Alignment.BottomStart)) }
            }
    }

    /** The previous chapter opens at its end, the next at its start. */
    private fun turnChapter(delta: Int) {
        val current = section ?: return
        lifecycleScope.launch { load(current.index + delta, if (delta < 0) 1.0 else 0.0) }
    }

    private fun runScript(script: String) = webView.evaluateJavascript(script, null)

    /** Upstream's page reports its position shortly after a scroll. */
    override fun reportPosition() = runScript("window.dispatchEvent(new Event('scroll'));")

    /** Pulling past a chapter's end or start ([PageGestures]) turns to the next or previous chapter. */
    private inner class PageTurns {
        @JavascriptInterface
        fun turn(delta: Int) {
            runOnUiThread { turnChapter(delta.coerceIn(-1, 1)) }
        }
    }

    /** Upstream reports the reading position as `incremento_epub_progress:{"scrollRatio": …}`. */
    private fun showProgress(
        current: Ankrimento.EpubSection,
        message: String,
    ) {
        if (!message.startsWith(PROGRESS_PREFIX)) return
        val data = runCatching { JSONObject(message.removePrefix(PROGRESS_PREFIX)) }.getOrNull()
        // The page index desktop counts for its focus timer.
        data?.optInt("pageIndex", -1)?.let { FocusTimer.epubPageRead(cardId, it) }
        showProgress(current, data?.optDouble("scrollRatio", 0.0) ?: 0.0)
    }

    private fun showProgress(
        current: Ankrimento.EpubSection,
        chapterRatio: Double,
    ) {
        val fraction = (current.index + chapterRatio.coerceIn(0.0, 1.0)) / current.count.coerceAtLeast(1)
        progress = ReaderProgress(fraction.toFloat(), getString(R.string.ankrimento_epub_chapter, current.index + 1, current.count))
    }

    /** The book's highlights; each change is applied, then the page and the sheet follow. */
    private fun changeHighlights(change: () -> String) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    val script = change()
                    script to Ankrimento.epubHighlights(cardId)
                }
            }.onSuccess { (script, highlights) ->
                if (script.isNotEmpty()) runScript(script)
                val named =
                    highlights.map { h ->
                        MarkHighlight(
                            h.id,
                            h.page,
                            h.text,
                            h.color,
                            h.note,
                            h.place.ifBlank { getString(R.string.ankrimento_epub_chapter_short, h.page + 1) },
                        )
                    }
                sheet = {
                    MarksSheet(
                        page = 0,
                        bookmarks = null,
                        highlights = named,
                        onOpenPage = {},
                        onOpenHighlight = { highlight ->
                            sheet = null
                            // The page script finds the highlighted words once the chapter loads.
                            pendingSearch = highlight.text.take(HIGHLIGHT_SEARCH_CHARS)
                            lifecycleScope.launch { load(highlight.page, 0.0) }
                        },
                        onAddBookmark = {},
                        onDeleteBookmark = {},
                        onHighlightColor = { id, color -> changeHighlights { Ankrimento.epubHighlightColor(cardId, id, color) } },
                        onHighlightNote = { id, note -> changeHighlights { Ankrimento.epubHighlightNote(cardId, id, note) } },
                        onDeleteHighlight = { id ->
                            val shown = section?.index ?: 0
                            changeHighlights { Ankrimento.epubDeleteHighlight(cardId, id, shown) }
                        },
                        onDismiss = { sheet = null },
                    )
                }
            }.onFailure { error ->
                Timber.w(error, "Changing EPUB highlights failed")
                toast(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun showFind() {
        sheet = {
            FindSheet(
                query = findQuery,
                hits =
                    findHits.map { hit ->
                        FindHit(hit.title.ifBlank { getString(R.string.ankrimento_epub_chapter_short, hit.section + 1) }, hit.snippet)
                    },
                status = findStatus,
                onSearch = ::search,
                onHit = { index ->
                    sheet = null
                    openHit(index)
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun search(query: String) {
        closeFind()
        findQuery = query
        findHits = emptyList()
        findStatus = if (query.isEmpty()) null else getString(R.string.ankrimento_find_searching)
        showFind()
        if (query.isEmpty()) return
        lifecycleScope.launch {
            val hits =
                runCatching { withCol { Ankrimento.epubSearch(cardId, query) } }
                    .onFailure { Timber.w(it, "EPUB search failed") }
                    .getOrDefault(emptyList())
            if (query != findQuery) return@launch
            findHits = hits
            findStatus = null
            if (sheet != null) showFind()
        }
    }

    /** Opens the chapter of match [index]; its page script finds and selects the words, as desktop's result does. */
    private fun openHit(index: Int) {
        val hit = findHits.getOrNull(index) ?: return
        // The find bar takes the place of the way back from a link.
        linkBack.clear()
        backFromLink.isEnabled = false
        findIndex = index
        pendingSearch = findQuery
        lifecycleScope.launch { load(hit.section, 0.0) }
        overlay = {
            FindBar(
                position = findIndex,
                total = findHits.size,
                onPrevious = { openHit(findIndex - 1) },
                onNext = { openHit(findIndex + 1) },
                onShowAll = ::showFind,
                onClose = ::closeFind,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    private fun closeFind() {
        if (overlay == null) return
        overlay = null
        findIndex = -1
        runScript("window.getSelection && window.getSelection().removeAllRanges();")
    }

    private fun showReadingSettings() {
        val book = reading ?: return
        sheet = {
            ReadingSettingsSheet(
                textScale = book.textScale,
                appearance = book.appearance,
                preferences = preferences,
                onTextScale = ::setTextScale,
                onAppearance = ::setAppearance,
                onPreferences = { chosen ->
                    preferences = chosen
                    chosen.save(this)
                    runScript(chosen.script())
                    showReadingSettings()
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun setTextScale(scale: Double) {
        lifecycleScope.launch {
            val kept = withCol { Ankrimento.epubSetTextScale(cardId, scale) }
            reading = reading?.copy(textScale = kept)
            runScript(
                "window.incrementoSetEpubTextScale && window.incrementoSetEpubTextScale($kept);" +
                    " window.dispatchEvent(new Event('resize'));",
            )
            showReadingSettings()
        }
    }

    private fun setAppearance(mode: String) {
        lifecycleScope.launch {
            withCol { Ankrimento.epubSetAppearance(cardId, mode) }
            reading = reading?.copy(appearance = mode)
            runScript("window.incrementoSetEpubAppearance && window.incrementoSetEpubAppearance(${JSONObject.quote(mode)});")
            showReadingSettings()
        }
    }

    private fun showContents() {
        val chapters = reading?.contents ?: return
        val current = section ?: return
        sheet = {
            ContentsSheet(
                chapters,
                current.index,
                onChapter = { index ->
                    sheet = null
                    lifecycleScope.launch { load(index, 0.0) }
                },
                onDismiss = { sheet = null },
            )
        }
    }

    /** Loads chapter [index] (the saved one when null) at [scrollRatio] (saved position when null). */
    private suspend fun load(
        index: Int?,
        scrollRatio: Double?,
    ) {
        val count = section?.count
        if (index != null && count != null && index !in 0 until count) return
        val opened =
            runCatching { withCol { Ankrimento.epubOpen(cardId, index) } }.getOrElse { error ->
                Timber.w(error, "Opening an EPUB chapter failed")
                Toast.makeText(this, R.string.ankrimento_epub_missing, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        section = opened
        subtitle = reading?.contents?.getOrNull(opened.index)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ankrimento_epub_chapter, opened.index + 1, opened.count)
        showProgress(opened, scrollRatio ?: 0.0)
        pendingScrollRatio = scrollRatio
        // Canonical: Incremento may resolve /data/user/0 to /data/data, or not.
        val root = File(opened.root).canonicalFile
        val assetLoader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/epub/", WebViewAssetLoader.InternalStoragePathHandler(this, root))
                .build()
        webView.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = request.url.host != ORIGIN_HOST

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    val current = section ?: return
                    lifecycleScope.launch {
                        val search = pendingSearch.also { pendingSearch = "" }
                        val fragment = pendingFragment.also { pendingFragment = "" }
                        val script = withCol { Ankrimento.epubScript(cardId, current, pendingScrollRatio, search, fragment) }
                        webView.evaluateJavascript(TOUCH_SELECTION_JS, null)
                        webView.evaluateJavascript(HIDE_READ_MARKER_JS, null)
                        webView.evaluateJavascript(script, null)
                        webView.evaluateJavascript(preferences.script(), null)
                        webView.evaluateJavascript(
                            PageGestures.script(
                                next = "AnkrimentoPages.turn(1);",
                                previous = "AnkrimentoPages.turn(-1);",
                                swipe = PageGestures.Swipe.SCREEN,
                                nextHint = getString(R.string.ankrimento_release_next_chapter),
                                previousHint = getString(R.string.ankrimento_release_previous_chapter),
                            ),
                            null,
                        )
                    }
                }
            }
        val relative = File(opened.path).canonicalFile.relativeTo(root).invariantSeparatorsPath
        webView.loadUrl("https://$ORIGIN_HOST/epub/" + relative.split('/').joinToString("/") { Uri.encode(it) })
    }

    override fun onDestroy() {
        messages.close()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Enough of a highlight's text for the page script to find it. */
        private const val HIGHLIGHT_SEARCH_CHARS = 120

        private const val ORIGIN_HOST = "appassets.androidplatform.net"

        /** Upstream's _PYCMD_BRIDGE; each message is prefix + nonce + ":" + message. */
        private const val MESSAGE_PREFIX = "__incremento_epub__:"
        private const val PROGRESS_PREFIX = "incremento_epub_progress:"

        /**
         * Upstream selects text by dragging a mouse and blocks native selection meanwhile;
         * a finger's drag scrolls instead. Touches on the text keep Android's long-press
         * selection (handles, and the Extract action); upstream's own controls still get theirs.
         */
        private const val TOUCH_SELECTION_JS =
            "window.addEventListener('pointerdown', function (event) {" +
                " var target = event.target; if (event.pointerType !== 'touch' || !target || !target.closest) return;" +
                " if (target.closest('[class*=\"incremento-epub-\"]" +
                ":not(.incremento-epub-highlight):not(.incremento-epub-scaled)')) return;" +
                " event.stopPropagation(); }, true);"

        private const val EXTRACT_JS = "window.incrementoFillEpubField && window.incrementoFillEpubField(0);"
        private const val HIGHLIGHT_JS = "window.incrementoAddEpubHighlight && window.incrementoAddEpubHighlight();"
        private const val READ_MARKER_JS = "window.incrementoToggleEpubReadMarker && window.incrementoToggleEpubReadMarker();"

        /** The read mark is kept (desktop shows it) but not drawn over the text on a phone. */
        private const val HIDE_READ_MARKER_JS =
            "(function () { var s = document.createElement('style');" +
                " s.textContent = '#incremento-epub-read-marker, .incremento-epub-read-marker-arrow { display: none !important; }';" +
                " document.head.appendChild(s); })();"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "epub", cardId, session = false)!!
    }
}
