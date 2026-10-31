// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.ContentsEntry
import com.ichi2.anki.ankrimento.ui.ContentsSheet
import com.ichi2.anki.ankrimento.ui.FindBar
import com.ichi2.anki.ankrimento.ui.FindHit
import com.ichi2.anki.ankrimento.ui.FindSheet
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.PdfViewSheet
import com.ichi2.anki.ankrimento.ui.ReaderPager
import com.ichi2.anki.ankrimento.ui.ReaderTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream

/**
 * Incremento's PDF reader: upstream's pdf.js viewer (`web/pdf_dock.html`),
 * unchanged, with its `pycmd` messages handled by Incremento's backend.
 *
 * The page, the viewer scripts and the PDF are served from one origin, so
 * upstream's Content-Security-Policy (`'self'` only) applies as on desktop.
 *
 * The screen is a phone's PDF reader: the page fills it and a tap leaves
 * only the page; the bar under it turns and scrubs pages; View holds page
 * colour (upstream's appearance, kept per document and synced) and zoom;
 * Extract is on the text-selection menu. Upstream's desktop panel stays one
 * tap away, for its annotation and link tools.
 *
 * Find searches upstream's per-page text index. Desktop fills it with PyMuPDF,
 * which the device lacks, so the viewer's pdf.js reads each page's text in the
 * background the first time (and when the file changes) and hands it to
 * upstream's indexer.
 */
class PdfReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var viewerControlsShown = false
    private var page = 0
    private var pages = 0
    private var zoom = 1.0
    private var appearance = "original"

    private var findQuery = ""
    private var findHits = emptyList<Ankrimento.PdfSearchHit>()
    private var findIndex = -1
    private var findStatus: String? = null
    private var textChecked = false
    private var readingText = false
    private val textPages = sortedMapOf<Int, String>()

    /** Viewer messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = ReaderWebView(this)
        webView.selectionActions =
            listOf(ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), EXTRACT_JS))
        setReaderContent(webView)
        toggleBarsOnTap(webView)
        showViewerControls(false)
        lifecycleScope.launch {
            val path = withCol { Ankrimento.pdfPath(cardId) }
            if (path.isEmpty()) {
                Toast.makeText(this@PdfReaderActivity, R.string.ankrimento_pdf_missing, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            openViewer(File(path))
        }
        Ankrimento.readerScope.launch {
            for (message in messages) {
                if (!isDestroyed) followViewer(message)
                if (message.startsWith(CONTENTS_PREFIX)) {
                    if (!isDestroyed) showContents(message.removePrefix(CONTENTS_PREFIX))
                    continue
                }
                val script = runCatching { withCol { Ankrimento.pdfMessage(cardId, message) } }
                script
                    .onSuccess { if (it.isNotEmpty() && !isDestroyed) webView.evaluateJavascript(it, null) }
                    .onFailure { Timber.w(it, "PDF viewer message failed: %s", message.substringBefore(':')) }
            }
        }
    }

    /**
     * Shows or hides upstream's own control panel. Hidden, the page has the
     * screen and the top bar holds the everyday tools; shown, every desktop
     * control (annotations, page links, bookmarks, the find box) is there.
     */
    private fun showViewerControls(show: Boolean) {
        viewerControlsShown = show
        webView.evaluateJavascript("document.body && document.body.classList.toggle('$COMPACT_CLASS', ${!show});", null)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_pdf_view), IncrementoIcons.Tune, ::showView),
                tool(
                    R.string.ankrimento_contents,
                    IncrementoIcons.Contents,
                    "window.incrementoPdfOpenContents && window.incrementoPdfOpenContents();",
                ),
                ReaderTool(getString(R.string.ankrimento_pdf_find), IncrementoIcons.Search, onClick = ::showFind),
                ReaderTool(getString(R.string.ankrimento_pdf_mark_read)) {
                    webView.evaluateJavascript("window.incrementoPdfMarkRead && window.incrementoPdfMarkRead();", null)
                    toast(getString(R.string.ankrimento_marked_read))
                },
                tool(R.string.ankrimento_extract_selection, null, EXTRACT_JS),
                ReaderTool(getString(if (show) R.string.ankrimento_pdf_hide_controls else R.string.ankrimento_pdf_show_controls)) {
                    showViewerControls(!viewerControlsShown)
                },
            )
    }

    private fun showFind() {
        sheet = {
            FindSheet(
                query = findQuery,
                hits = findHits.map { FindHit(it.page, it.snippet) },
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
        findStatus = if (query.isEmpty()) null else getString(R.string.ankrimento_find_searching)
        showFind()
        if (query.isEmpty()) return
        lifecycleScope.launch {
            val (hits, state) =
                runCatching { withCol { Ankrimento.pdfSearch(cardId, query) to Ankrimento.pdfIndexState(cardId) } }
                    .getOrElse { error ->
                        Timber.w(error, "PDF search failed")
                        emptyList<Ankrimento.PdfSearchHit>() to ""
                    }
            if (query != findQuery) return@launch
            findHits = hits
            findStatus =
                when {
                    readingText -> getString(R.string.ankrimento_find_reading)
                    hits.isEmpty() && state == "empty" -> getString(R.string.ankrimento_find_no_text)
                    else -> null
                }
            if (sheet != null) showFind()
        }
    }

    /** Opens the page of match [index], with the matches highlighted as desktop's find shows them. */
    private fun openHit(index: Int) {
        val hit = findHits.getOrNull(index) ?: return
        findIndex = index
        goToPage(hit.page)
        val hits = JSONArray(findHits.map { JSONObject().put("page", it.page).put("snippet", it.snippet).put("excerpt", it.excerpt) })
        val state = JSONObject().put("query", findQuery).put("hits", hits).put("activeIndex", index)
        // After the page turn, so the viewer scrolls to the match on the page it shows.
        webView.postDelayed(
            {
                webView.evaluateJavascript(
                    "window.incrementoReceivePdfSearchState && window.incrementoReceivePdfSearchState($state);",
                    null,
                )
            },
            HIT_DELAY_MS,
        )
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
        webView.evaluateJavascript(
            "window.incrementoReceivePdfSearchState && window.incrementoReceivePdfSearchState({query: '', hits: [], activeIndex: -1});",
            null,
        )
    }

    /**
     * Reads the document's text for find when upstream's index lacks it: never read, or the file
     * changed. Upstream's import records "empty" when it has nothing to read text with, as here,
     * so the PDFs this device has read are remembered.
     */
    private fun ensureTextIndex() {
        if (textChecked) return
        textChecked = true
        lifecycleScope.launch {
            val state = runCatching { withCol { Ankrimento.pdfIndexState(cardId) } }.getOrDefault("current")
            val readHere = cardId.toString() in textRead()
            if (state == "stale" || (state == "empty" && !readHere)) {
                readingText = true
                webView.evaluateJavascript(EXTRACT_TEXT_JS, null)
            }
        }
    }

    private fun textRead(): Set<String> = getSharedPreferences(TEXT_PREFERENCES, MODE_PRIVATE).getStringSet(KEY_TEXT_READ, null).orEmpty()

    private fun indexText() {
        val pages = synchronized(textPages) { textPages.values.toList() }
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.pdfIndexPages(cardId, pages) } }
                .onSuccess {
                    getSharedPreferences(TEXT_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putStringSet(KEY_TEXT_READ, textRead() + cardId.toString())
                        .apply()
                }.onFailure { Timber.w(it, "Indexing the PDF's text failed") }
            readingText = false
            if (findQuery.isNotEmpty() && findIndex < 0) search(findQuery)
        }
    }

    /** The viewer reports its scroll position shortly after a scroll. */
    override fun reportPosition() = webView.evaluateJavascript("window.dispatchEvent(new Event('scroll'));", null)

    private fun tool(
        label: Int,
        icon: ImageVector?,
        script: String,
    ) = ReaderTool(getString(label), icon) { webView.evaluateJavascript(script, null) }

    /** Upstream reports page turns as `incremento_pdf_nav:<card>:<page>` and zoom as `incremento_pdf_zoom:<card>:<zoom>`. */
    private fun followViewer(message: String) {
        val parts = message.split(':')
        if (parts.size != 3) return
        when (parts[0]) {
            "incremento_pdf_nav" -> parts[2].toIntOrNull()?.let { showPage(it, pages) }
            "incremento_pdf_zoom" -> parts[2].toDoubleOrNull()?.let { zoom = it }
        }
    }

    private fun showPage(
        current: Int,
        total: Int,
    ) {
        page = current
        pages = total
        pager = if (current > 0 && total > 0) ReaderPager(current, total, ::goToPage) else null
    }

    /** Turns pages as upstream's arrows do, so its daily page limit still applies. */
    private fun goToPage(target: Int) {
        val delta = target.coerceIn(1, pages.coerceAtLeast(1)) - page
        if (delta != 0) webView.evaluateJavascript("window.incrementoPdfNav && window.incrementoPdfNav($delta);", null)
    }

    /** Reads the page, page count, zoom and appearance from the viewer, until it has the document. */
    private fun readViewerState(attempt: Int = 0) {
        webView.evaluateJavascript(STATE_JS) { result ->
            val state = runCatching { JSONObject(JSONArray("[$result]").getString(0)) }.getOrNull()
            val total = state?.optInt("pages", 0) ?: 0
            if (state == null || total <= 0) {
                if (attempt < STATE_ATTEMPTS) webView.postDelayed({ readViewerState(attempt + 1) }, STATE_RETRY_MS)
                return@evaluateJavascript
            }
            zoom = state.optDouble("zoom", 1.0)
            appearance = state.optString("appearance", "original")
            showPage(state.optInt("page", 1), total)
            ensureTextIndex()
        }
    }

    /**
     * The viewer's outline (it sends it when asked, as desktop's contents
     * popup receives it); choosing an entry jumps through the viewer's own
     * contents jump, which also keeps the daily page limit.
     */
    private fun showContents(payload: String) {
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val token = data.optInt("token", -1)
        val raw = data.optJSONArray("entries") ?: JSONArray()
        val entries = List(raw.length()) { raw.getJSONObject(it) }
        val shown =
            entries.map {
                ContentsEntry(it.optString("title"), it.optInt("depth"), getString(R.string.ankrimento_page_short, it.optInt("page")))
            }
        val current = entries.indexOfLast { it.optInt("page") <= page }
        sheet = {
            ContentsSheet(shown, current, numbered = false, onChapter = { index ->
                sheet = null
                webView.evaluateJavascript(
                    "window.incrementoPdfContentsJump && window.incrementoPdfContentsJump(${entries[index]}, $token);",
                    null,
                )
            }, onDismiss = { sheet = null })
        }
    }

    private fun showView() {
        sheet = {
            PdfViewSheet(
                appearance = appearance,
                zoom = zoom,
                onAppearance = { mode ->
                    appearance = mode
                    // Upstream's appearance menu: apply, then store it for this document.
                    webView.evaluateJavascript(
                        "window.incrementoSetPdfAppearanceMode && window.incrementoSetPdfAppearanceMode(${JSONObject.quote(mode)});" +
                            " window.pycmd('incremento_pdf_appearance:' + JSON.stringify({cardId: $cardId, mode: ${JSONObject.quote(
                                mode,
                            )}}));",
                        null,
                    )
                    showView()
                },
                onZoom = { direction ->
                    webView.evaluateJavascript("window.incrementoPdfZoom && window.incrementoPdfZoom($direction);") { readViewerState() }
                    zoom = (zoom + direction * ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
                    showView()
                },
                onFitWidth = {
                    // Upstream's zoom is relative to the page width; 100% fits it. Zoom steps are a tenth
                    // from wherever zoom is (25% at the minimum is off that grid), so go to the 400%
                    // maximum first: thirty steps down from there are exactly 100%.
                    val stepsToMax = kotlin.math.ceil((ZOOM_MAX - ZOOM_MIN) / ZOOM_STEP).toInt()
                    val stepsDown = kotlin.math.round((ZOOM_MAX - 1.0) / ZOOM_STEP).toInt()
                    webView.evaluateJavascript(
                        "if (window.incrementoPdfZoom) { window.incrementoPdfZoom($stepsToMax); window.incrementoPdfZoom(-$stepsDown); }",
                        null,
                    )
                    zoom = 1.0
                    showView()
                },
                onDismiss = { sheet = null },
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openViewer(pdf: File) {
        val assetLoader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/ankrimento-pdf/") {
                    WebResourceResponse("application/pdf", null, FileInputStream(pdf))
                }.build()
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    if (url == VIEWER_URL) startViewer()
                }
            }
        webView.addJavascriptInterface(Bridge(), "AnkrimentoPdfHost")
        webView.addJavascriptInterface(TextBridge(), "AnkrimentoPdfText")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, PYCMD_SCRIPT, setOf(ORIGIN))
        } else {
            Timber.w("WebView lacks document-start scripts; early viewer messages may be lost")
        }
        webView.loadUrl(VIEWER_URL)
    }

    private fun startViewer() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            webView.evaluateJavascript(PYCMD_SCRIPT, null)
        }
        lifecycleScope.launch {
            val script = withCol { Ankrimento.pdfStartScript(cardId, PDF_URL, WORKER_URL) }
            webView.evaluateJavascript(script, null)
            webView.evaluateJavascript(COMPACT_STYLE_JS, null)
            webView.evaluateJavascript(PINCH_ZOOM_JS, null)
            webView.evaluateJavascript(
                PageGestures.script(
                    next = "window.incrementoPdfNav && window.incrementoPdfNav(1);",
                    previous = "window.incrementoPdfNav && window.incrementoPdfNav(-1);",
                    swipe = PageGestures.Swipe.TURN,
                    nextHint = getString(R.string.ankrimento_release_next_page),
                    previousHint = getString(R.string.ankrimento_release_previous_page),
                ),
                null,
            )
            showViewerControls(viewerControlsShown)
            readViewerState()
        }
    }

    override fun onExtractCreated(script: String) {
        if (script.isNotEmpty()) webView.evaluateJavascript(script, null)
    }

    override fun onDestroy() {
        messages.close()
        webView.destroy()
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun pycmd(message: String) {
            messages.trySend(message)
        }
    }

    /** Receives the page texts the viewer's pdf.js reads for find. */
    private inner class TextBridge {
        @JavascriptInterface
        fun page(
            number: Int,
            text: String,
        ) {
            synchronized(textPages) { textPages[number] = text }
        }

        @JavascriptInterface
        fun done() {
            runOnUiThread { if (!isDestroyed) indexText() }
        }

        @JavascriptInterface
        fun failed(message: String) {
            Timber.w("Reading the PDF's text failed: %s", message)
            readingText = false
        }
    }

    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val VIEWER_URL = "$ORIGIN/assets/ankrimento/web/pdf_dock.html"
        private const val WORKER_URL = "$ORIGIN/assets/ankrimento/web/pdfjs/pdf.worker.min.js"
        private const val PDF_URL = "$ORIGIN/ankrimento-pdf/document.pdf"

        /** Desktop's `pycmd`, and touch selection, set up before the viewer's scripts run. */
        private const val PYCMD_SCRIPT =
            "window.pycmd = function (message) { AnkrimentoPdfHost.pycmd(String(message)); return false; };" +
                // Upstream selects text by dragging a mouse and blocks native selection meanwhile;
                // a finger's drag scrolls instead. Touches on the text keep Android's long-press
                // selection (handles, and the Extract action), which never reaches upstream's drag.
                " window.addEventListener('pointerdown', function (event) {" +
                " if (event.pointerType === 'touch' && event.target && event.target.closest &&" +
                " event.target.closest('#pdf-text-layer')) event.stopPropagation(); }, true);"

        /**
         * Two-finger zoom: the page follows the fingers while they move, then
         * upstream's zoom steps are applied (so the zoom is saved per document).
         */
        private const val PINCH_ZOOM_JS =
            "(function () { if (window.__ankrimentoPinch) return; window.__ankrimentoPinch = true;" +
                // The page handles pinches itself; the browser would take the gesture after the first move.
                " document.documentElement.style.touchAction = 'pan-x pan-y'; document.body.style.touchAction = 'pan-x pan-y';" +
                " var start = 0, scale = 1, page = null;" +
                " function spread(t) { return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }" +
                " window.addEventListener('touchstart', function (e) { if (e.touches.length !== 2) return;" +
                " start = spread(e.touches); scale = 1; page = document.getElementById('pdf-canvas-wrapper'); }, { passive: true });" +
                " window.addEventListener('touchmove', function (e) { if (!start || e.touches.length !== 2) return;" +
                " scale = Math.max(0.25, Math.min(4, spread(e.touches) / start));" +
                " if (page) { page.style.transformOrigin = 'center top';" +
                " page.style.transform = 'scale(' + scale + ')'; } }, { passive: true });" +
                " window.addEventListener('touchend', function (e) { if (!start || e.touches.length >= 2) return; start = 0;" +
                " if (page) page.style.transform = '';" +
                " var state = window.incrementoPdfWorkspaceState ? window.incrementoPdfWorkspaceState() : null;" +
                " var zoom = state && state.zoom ? state.zoom : 1; var steps = Math.round((zoom * scale - zoom) / 0.1);" +
                " if (steps && window.incrementoPdfZoom) window.incrementoPdfZoom(steps); }, { passive: true }); })();"

        /** Every page's text through the viewer's pdf.js, one page at a time, yielding between pages. */
        private const val EXTRACT_TEXT_JS =
            "(async function () { if (window.__ankrimentoText) return; window.__ankrimentoText = true;" +
                " try { var doc = await window.pdfjsLib.getDocument({url: '$PDF_URL', isEvalSupported: false}).promise;" +
                " for (var i = 1; i <= doc.numPages; i++) { var page = await doc.getPage(i);" +
                " var content = await page.getTextContent();" +
                " AnkrimentoPdfText.page(i, content.items.map(function (item) {" +
                " return (item.str || '') + (item.hasEOL ? '\\n' : ' '); }).join(''));" +
                " page.cleanup(); await new Promise(function (r) { setTimeout(r, 0); }); }" +
                " AnkrimentoPdfText.done(); doc.destroy();" +
                " } catch (e) { AnkrimentoPdfText.failed(String((e && e.message) || e)); } })();"

        /** Long enough for the page turn before the viewer is told which match to show. */
        private const val HIT_DELAY_MS = 450L

        /** The PDFs whose text this device read itself (upstream records them as "empty" otherwise). */
        private const val TEXT_PREFERENCES = "ankrimento_pdf_text"
        private const val KEY_TEXT_READ = "read"

        /** The viewer's request to show its outline, as desktop's contents popup gets it. */
        private const val CONTENTS_PREFIX = "incremento_pdf_contents:"

        /** Marks the page while upstream's control panel is hidden. */
        private const val COMPACT_CLASS = "ankrimento-compact"
        private const val COMPACT_STYLE_JS =
            "(function () { if (document.getElementById('ankrimento-style')) return;" +
                " var style = document.createElement('style'); style.id = 'ankrimento-style';" +
                " style.textContent = 'body.$COMPACT_CLASS #pdf-controls { display: none !important; }';" +
                " document.head.appendChild(style); })();"

        /** The viewer's page and zoom, its appearance, and the page count from its "page / pages" indicator. */
        private const val STATE_JS =
            "(function () { var s = window.incrementoPdfWorkspaceState ? window.incrementoPdfWorkspaceState() : null;" +
                " if (!s) return null; var pages = 0; var c = document.getElementById('pdf-controls');" +
                " if (c) { var b = Array.prototype.slice.call(c.querySelectorAll('button'));" +
                " for (var i = 0; i < b.length; i++) { var m = /(\\d+)\\s*\\/\\s*(\\d+)/.exec(b[i].textContent || '');" +
                " if (m) { pages = Number(m[2]); break; } } }" +
                " return JSON.stringify({page: s.page, zoom: s.zoom, pages: pages," +
                " appearance: s.controls && s.controls.appearance}); })()"
        private const val STATE_ATTEMPTS = 20
        private const val STATE_RETRY_MS = 500L

        /** Upstream's zoom steps (ZOOM_STEP, ZOOM_MIN, ZOOM_MAX in the viewer). */
        private const val ZOOM_STEP = 0.1
        private const val ZOOM_MIN = 0.25
        private const val ZOOM_MAX = 4.0

        /** Desktop's first extract shortcut on the selection. */
        private const val EXTRACT_JS = "window.incrementoHandleExtractShortcut && window.incrementoHandleExtractShortcut(0);"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "pdf", cardId, session = false)!!
    }
}
