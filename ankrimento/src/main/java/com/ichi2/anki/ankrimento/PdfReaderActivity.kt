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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
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
import com.ichi2.anki.ankrimento.ui.HighlightCardsSheet
import com.ichi2.anki.ankrimento.ui.HighlightColorDialog
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.LinkTargetSheet
import com.ichi2.anki.ankrimento.ui.MarksSheet
import com.ichi2.anki.ankrimento.ui.PageLimitDialog
import com.ichi2.anki.ankrimento.ui.PageLinksSheet
import com.ichi2.anki.ankrimento.ui.PdfViewSheet
import com.ichi2.anki.ankrimento.ui.ReaderPager
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.SnapshotLayer
import com.ichi2.anki.common.destinations.BrowserDestination
import com.ichi2.anki.common.destinations.DeferredNavigation
import com.ichi2.anki.common.destinations.toIntent
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
 * Page links (desktop's connections between PDF pages) have their own sheet. A
 * link is made by opening the destination PDF in a mode that only chooses the
 * place, as desktop's destination dialog: nothing it does is saved for that PDF.
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

    override val reviewKind = "pdf"

    override fun reviewPosition() = page.coerceAtLeast(1).toDouble()

    private var pages = 0
    private var zoom = 1.0

    /** The PDF whose page is being linked when this reader only chooses the destination (0 otherwise). */
    private val linkSource by lazy { intent.getLongExtra(EXTRA_LINK_FROM, 0L) }
    private val choosingDestination get() = linkSource != 0L
    private var scrollRatio = 0.0
    private var appearance = "original"

    private var findQuery = ""
    private var findHits = emptyList<Ankrimento.PdfSearchHit>()
    private var findIndex = -1
    private var findStatus: String? = null
    private var textChecked = false
    private var startHandled = false
    private var readingText = false
    private val textPages = sortedMapOf<Int, String>()

    /** Viewer messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = ReaderWebView(this)
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), EXTRACT_JS),
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_highlight), HIGHLIGHT_JS),
            )
        setReaderContent(webView)
        toggleBarsOnTap(webView)
        showViewerControls(false)
        if (choosingDestination) chooseDestination()
        lifecycleScope.launch {
            val path = withCol { Ankrimento.pdfPath(cardId) }
            if (path.isEmpty()) {
                this@PdfReaderActivity.showToast(R.string.ankrimento_pdf_missing)
                finish()
                return@launch
            }
            openViewer(File(path))
        }
        Ankrimento.readerScope.launch {
            for (message in messages) {
                if (!isDestroyed) followViewer(message)
                if (choosingDestination && DESTINATION_KEEPS_NOTHING.any { message.startsWith(it) }) continue
                if (message.startsWith(HIGHLIGHT_NOTE_PREFIX)) {
                    val id = runCatching { JSONObject(message.removePrefix(HIGHLIGHT_NOTE_PREFIX)).optString("id") }.getOrDefault("")
                    if (!isDestroyed) runOnUiThread { changeMarks(openHighlight = id.ifBlank { null }) { "" } }
                    continue
                }
                if (!isDestroyed && cardButton(message)) continue
                if (message.startsWith(HIGHLIGHT_COLOR_PREFIX)) {
                    val data = message.removePrefix(HIGHLIGHT_COLOR_PREFIX)
                    if (!isDestroyed) runOnUiThread { chooseColor(data) }
                    continue
                }
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
        val finding =
            listOf(
                ReaderTool(getString(R.string.ankrimento_pdf_view), IncrementoIcons.Tune, onClick = ::showView),
                tool(
                    R.string.ankrimento_contents,
                    IncrementoIcons.Contents,
                    "window.incrementoPdfOpenContents && window.incrementoPdfOpenContents();",
                ),
                ReaderTool(getString(R.string.ankrimento_pdf_find), IncrementoIcons.Search, onClick = ::showFind),
            )
        // Choosing a link's destination needs only the tools that find the place.
        if (choosingDestination) {
            tools = finding
            return
        }
        tools =
            finding +
            listOf(
                ReaderTool(getString(R.string.ankrimento_marks), IncrementoIcons.Bookmark, onClick = ::showMarks),
                ReaderTool(getString(R.string.ankrimento_page_links), IncrementoIcons.Link, onClick = ::showPageLinks),
                ReaderTool(getString(R.string.ankrimento_pdf_mark_read)) {
                    webView.evaluateJavascript("window.incrementoPdfMarkRead && window.incrementoPdfMarkRead();", null)
                    toast(getString(R.string.ankrimento_marked_read))
                },
                tool(R.string.ankrimento_extract_selection, null, EXTRACT_JS),
                ReaderTool(getString(R.string.ankrimento_pdf_snapshot), onClick = ::startSnapshot),
                ReaderTool(getString(if (show) R.string.ankrimento_pdf_hide_controls else R.string.ankrimento_pdf_show_controls)) {
                    showViewerControls(!viewerControlsShown)
                },
            )
    }

    /** The document's bookmarks and highlights; each change is applied, then the viewer and the sheet follow. */
    private fun showMarks() {
        changeMarks { "" }
    }

    private fun changeMarks(
        openHighlight: String? = null,
        change: () -> String,
    ) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    val script = change()
                    script to Ankrimento.pdfMarks(cardId)
                }
            }.onSuccess { (script, marks) ->
                if (script.isNotEmpty()) webView.evaluateJavascript(script, null)
                sheet = {
                    MarksSheet(
                        page = page,
                        bookmarks = marks.bookmarks,
                        highlights = marks.highlights,
                        onOpenPage = { target ->
                            sheet = null
                            goToPage(target)
                        },
                        onAddBookmark = { changeMarks { Ankrimento.pdfAddBookmark(cardId, page) } },
                        onDeleteBookmark = { id -> changeMarks { Ankrimento.pdfDeleteBookmark(cardId, id) } },
                        onHighlightColor = { id, color -> changeMarks { Ankrimento.pdfHighlightColor(cardId, id, color) } },
                        onHighlightNote = { id, note -> changeMarks { Ankrimento.pdfHighlightNote(cardId, id, note) } },
                        onHighlightStyle = { id, style -> changeMarks { Ankrimento.pdfHighlightStyle(cardId, id, style) } },
                        onBookmarkComment = { id, comment -> changeMarks { Ankrimento.pdfBookmarkComment(cardId, id, comment) } },
                        onDeleteHighlight = { id -> changeMarks { Ankrimento.pdfDeleteHighlight(cardId, id) } },
                        onDismiss = { sheet = null },
                        openHighlight = openHighlight,
                    )
                }
            }.onFailure { error ->
                Timber.w(error, "Changing PDF bookmarks or highlights failed")
                toast(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /**
     * The viewer's card buttons, answered as desktop answers them with the phone's screens: a highlight's
     * card (or the sheet to make it), cards from many highlights, a card listed on the page, the page's or
     * the document's cards in AnkiDroid's browser, Add Card, and the daily page limit.
     */
    private fun cardButton(message: String): Boolean {
        val payload = message.substringAfter(':', "")
        when {
            message.startsWith(HIGHLIGHT_CARD_PREFIX) -> {
                val id = runCatching { JSONObject(payload).optString("id") }.getOrDefault("")
                withPdf({ Ankrimento.pdfHighlightCard(cardId, id) }) { result ->
                    when {
                        result.has("card_id") -> openCard(result.getLong("card_id"))
                        result.has("message") -> toast(result.getString("message"))
                        // A draft: the extract sheet follows its event.
                    }
                }
            }
            message == HIGHLIGHT_CARDS -> withPdf({ Ankrimento.pdfHighlightCardsPlan(cardId) }, ::showHighlightCards)
            message.startsWith(OPEN_CARD_PREFIX) ->
                payload.toLongOrNull()?.let { noteId ->
                    withPdf({ Ankrimento.noteFirstCard(noteId) }) { card -> if (card > 0) openCard(card) }
                }
            message.startsWith(PAGE_CARDS_PREFIX) -> withPdf({ Ankrimento.pdfNoteCards(payload, "page") }, ::browse)
            message.startsWith(ALL_CARDS_PREFIX) -> withPdf({ Ankrimento.pdfDocumentCards(cardId) }, ::browse)
            message == ADD_CARD -> withPdf({ Ankrimento.pdfNewCard(cardId) }) {}
            message.startsWith(LIMIT_SETTINGS_PREFIX) -> withPdf({ Ankrimento.pdfLimitSettings(cardId) }, ::showPageLimit)
            else -> return false
        }
        return true
    }

    private fun <T> withPdf(
        call: com.ichi2.anki.libanki.Collection.() -> T,
        then: (T) -> Unit,
    ) {
        lifecycleScope.launch {
            runCatching { withCol { call() } }
                .onSuccess(then)
                .onFailure { error ->
                    Timber.w(error, "PDF viewer card button failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    /** A card to look at, in the reader that shows it (as the knowledge tree opens one). */
    private fun openCard(card: Long) {
        lifecycleScope.launch {
            val reader = withCol { Ankrimento.cardReader(card) }
            ReaderActivity.intent(this@PdfReaderActivity, reader.ifEmpty { "item" }, card, session = false)?.let(::startActivity)
        }
    }

    /** AnkiDroid's browser on the search, or desktop's message when there is nothing to show. */
    private fun browse(result: JSONObject) {
        val query = result.optString("query")
        if (query.isEmpty()) {
            toast(result.optString("message"))
            return
        }
        startActivity(with(DeferredNavigation) { BrowserDestination.Search(query, allDecks = true).toIntent() })
    }

    private fun showHighlightCards(plan: Ankrimento.HighlightCardsPlan) {
        if (plan.rows.isEmpty()) {
            toast(plan.message)
            return
        }
        sheet = {
            HighlightCardsSheet(
                plan = plan,
                onCreate = { ids, noteType, isTopic ->
                    sheet = null
                    withPdf({ Ankrimento.pdfHighlightCardsCreate(cardId, ids, noteType, isTopic) }) { (summary, script) ->
                        toast(summary)
                        // The viewer shows which highlights now have a card.
                        webView.evaluateJavascript(script, null)
                    }
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun showPageLimit(limit: Ankrimento.PageLimit) {
        sheet = {
            PageLimitDialog(
                limit = limit,
                onSave = { enabled, pages, mode ->
                    sheet = null
                    withPdf({ Ankrimento.pdfSaveLimit(cardId, enabled, pages, mode) }) { (script, saved) ->
                        webView.evaluateJavascript(script, null)
                        toast(saved)
                    }
                },
                onDismiss = { sheet = null },
            )
        }
    }

    /** The viewer's colour button, answered with the phone's colours; the viewer waits for the reply. */
    private fun chooseColor(data: String) {
        val request = runCatching { JSONObject(data) }.getOrNull() ?: JSONObject()
        sheet = {
            HighlightColorDialog(
                current = request.optString("currentColor"),
                forDefault = request.optBoolean("defaultColor"),
            ) { chosen ->
                sheet = null
                lifecycleScope.launch {
                    runCatching { withCol { Ankrimento.pdfChooseColor(cardId, data, chosen) } }
                        .onSuccess { webView.evaluateJavascript(it, null) }
                        .onFailure { error ->
                            Timber.w(error, "Choosing a highlight colour failed")
                            toast(error.message ?: error.javaClass.simpleName)
                        }
                }
            }
        }
    }

    private fun showFind() {
        sheet = {
            FindSheet(
                query = findQuery,
                hits = findHits.map { FindHit(getString(R.string.ankrimento_page_short, it.page), it.snippet) },
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

    /** Opens the page and words asked for (Search ALL): the match on that page, highlighted. */
    private fun openStart() {
        val start = intent.getIntExtra(EXTRA_START, 0)
        val query = intent.getStringExtra(EXTRA_FIND).orEmpty().trim()
        if (query.isEmpty()) {
            if (start > 0) goToPage(start)
            return
        }
        lifecycleScope.launch {
            val hits = runCatching { withCol { Ankrimento.pdfSearch(cardId, query) } }.getOrDefault(emptyList())
            findQuery = query
            findHits = hits
            val index = hits.indexOfFirst { it.page == start }
            when {
                index >= 0 -> openHit(index)
                start > 0 -> goToPage(start)
            }
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

    private fun textRead(): Set<String> = getSharedPreferences(TEXT_PREFERENCES, MODE_PRIVATE).getStringSet(PREF_TEXT_READ, null).orEmpty()

    private fun indexText() {
        val pages = synchronized(textPages) { textPages.values.toList() }
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.pdfIndexPages(cardId, pages) } }
                .onSuccess {
                    getSharedPreferences(TEXT_PREFERENCES, MODE_PRIVATE).edit {
                        putStringSet(PREF_TEXT_READ, textRead() + cardId.toString())
                    }
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
        if (message.startsWith(SCROLL_PREFIX)) {
            runCatching { JSONObject(message.removePrefix(SCROLL_PREFIX)).optDouble("scrollRatio", 0.0) }
                .getOrNull()
                ?.let { scrollRatio = it.coerceIn(0.0, 1.0) }
            return
        }
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
        if (!choosingDestination) FocusTimer.pdfPageRead(cardId, current)
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
            if (!startHandled) {
                startHandled = true
                openStart()
            }
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

    /** The page's links: each opens its other end or is removed; the page can be linked to another. */
    private fun showPageLinks() {
        val shown = page.coerceAtLeast(1)
        lifecycleScope.launch {
            val links =
                runCatching { withCol { Ankrimento.pdfPageLinks(cardId, shown) } }.getOrElse { error ->
                    Timber.w(error, "Loading the page's links failed")
                    toast(error.message ?: error.javaClass.simpleName)
                    return@launch
                }
            sheet = {
                PageLinksSheet(
                    page = shown,
                    links = links,
                    onOpen = { link ->
                        sheet = null
                        openLink(shown, link)
                    },
                    onRemove = { link ->
                        lifecycleScope.launch {
                            runCatching { withCol { Ankrimento.pdfPageLinkRemove(cardId, shown, link) } }
                                .onFailure { Timber.w(it, "Removing a page link failed") }
                            refreshViewerLinks()
                            showPageLinks()
                        }
                    },
                    onAdd = { chooseLinkTarget(shown, null) },
                    onDismiss = { sheet = null },
                )
            }
        }
    }

    private fun openLink(
        shown: Int,
        link: Ankrimento.PageLink,
    ) {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.pdfPageLinkTarget(cardId, shown, link) } }
                .onSuccess(::openPlace)
                .onFailure {
                    Timber.w(it, "Opening a page link failed")
                    toast(getString(R.string.ankrimento_page_link_unavailable))
                }
        }
    }

    /** A link's other end: a page here, or another PDF's reader at that page. */
    private fun openPlace(place: Ankrimento.PdfPlace) {
        if (place.cardId == cardId) {
            goToPage(place.page)
        } else {
            ReaderActivity.intentAt(this, "pdf", place.cardId, place.page, "")?.let(::startActivity)
        }
    }

    /** Desktop's bookshelf of PDFs, then the chosen one opened to choose the place ([chooseDestination]). */
    private fun chooseLinkTarget(
        sourcePage: Int,
        sourceY: Double?,
    ) {
        lifecycleScope.launch {
            val documents =
                runCatching { withCol { Ankrimento.library() } }
                    .getOrDefault(emptyList())
                    .filter { it.kind == "pdf" }
            sheet = {
                LinkTargetSheet(
                    documents = documents,
                    onPick = { document ->
                        sheet = null
                        startActivity(destinationIntent(this@PdfReaderActivity, document.cardId, cardId, sourcePage, sourceY))
                    },
                    onDismiss = { sheet = null },
                )
            }
        }
    }

    /** Only choosing a place: the reader's tools give way to one button that links the source page here. */
    private fun chooseDestination() {
        webView.selectionActions = emptyList()
        subtitle = getString(R.string.ankrimento_page_link_choose_place)
        overlay = {
            Button(
                onClick = ::linkHere,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { Text(stringResource(R.string.ankrimento_page_link_here)) }
        }
    }

    private fun linkHere() {
        val sourcePage = intent.getIntExtra(EXTRA_LINK_PAGE, 1)
        val sourceY = intent.getDoubleExtra(EXTRA_LINK_Y, -1.0).takeIf { it >= 0 }
        val place = Ankrimento.PdfPlace(cardId, page.coerceAtLeast(1), scrollRatio)
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.pdfPageLinkAdd(linkSource, sourcePage, sourceY, place) } }
                .onSuccess {
                    toast(getString(R.string.ankrimento_page_linked))
                    finish()
                }.onFailure {
                    Timber.w(it, "Linking a page failed")
                    toast(getString(R.string.ankrimento_page_link_unavailable))
                }
        }
    }

    private fun refreshViewerLinks() =
        webView.evaluateJavascript("window.incrementoRefreshPdfPageLinks && window.incrementoRefreshPdfPageLinks();", null)

    override fun onResume() {
        super.onResume()
        // A link made from another reader shows in the viewer's own panel too.
        if (::webView.isInitialized) refreshViewerLinks()
    }

    override fun onDocumentEvent(event: Ankrimento.Event) {
        if (choosingDestination) return
        when (event) {
            is Ankrimento.Event.PdfOpen -> if (event.sourceCardId == cardId) openPlace(event.place)
            is Ankrimento.Event.PdfPageLinks ->
                if (event.cardId == cardId) {
                    if (event.create) chooseLinkTarget(event.page, event.pageY) else showPageLinks()
                }
            else -> Unit
        }
    }

    /**
     * Desktop's snapshot: the box dragged over the page is drawn from the page as shown and handed
     * to Incremento as the viewer's own snapshot message, so it is kept as desktop keeps it (media,
     * then the extract with the page's link). The viewer's snapshot button sits in a desktop panel
     * and expects a mouse; a layer over the page takes the finger instead.
     */
    private fun startSnapshot() {
        toast(getString(R.string.ankrimento_pdf_snapshot_hint))
        overlay = {
            SnapshotLayer(
                onBox = { box ->
                    overlay = null
                    takeSnapshot(box)
                },
                onCancel = { overlay = null },
            )
        }
    }

    private fun takeSnapshot(box: androidx.compose.ui.geometry.Rect) {
        lifecycleScope.launch {
            val image = RegionSnapshot.capture(this@PdfReaderActivity, webView, box)
            if (image == null) {
                toast(getString(R.string.ankrimento_extract_failed, "PixelCopy"))
                return@launch
            }
            val snapshot = JSONObject().put("cardId", cardId).put("page", page.coerceAtLeast(1)).put("image", image)
            messages.trySend("incremento_pdf_snapshot:$snapshot")
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

        /** Desktop's highlight shortcut (Ctrl+H), which highlights the selection in the default colour. */
        private const val HIGHLIGHT_JS =
            "window.dispatchEvent(new KeyboardEvent('keydown', {code: 'KeyH', key: 'h', ctrlKey: true, bubbles: true}));"

        /** Long enough for the page turn before the viewer is told which match to show. */
        private const val HIT_DELAY_MS = 450L

        /** The PDFs whose text this device read itself (upstream records them as "empty" otherwise). */
        private const val TEXT_PREFERENCES = "ankrimento_pdf_text"
        private const val PREF_TEXT_READ = "read"

        /** The viewer's request to show its outline, as desktop's contents popup gets it. */
        private const val CONTENTS_PREFIX = "incremento_pdf_contents:"

        /** The viewer's note button on a highlight: the phone's highlight dialog answers it. */
        private const val HIGHLIGHT_NOTE_PREFIX = "incremento_pdf_hl_note:"

        // The viewer's card buttons (see cardButton).
        private const val HIGHLIGHT_CARD_PREFIX = "incremento_pdf_hl_card:"
        private const val HIGHLIGHT_CARDS = "incremento_pdf_hl_bulk_cards"
        private const val OPEN_CARD_PREFIX = "incremento_open_card:"
        private const val PAGE_CARDS_PREFIX = "incremento_open_page_cards:"
        private const val ALL_CARDS_PREFIX = "incremento_open_all_pdf_cards:"
        private const val ADD_CARD = "incremento_open_add_card"
        private const val LIMIT_SETTINGS_PREFIX = "incremento_pdf_limit_settings:"

        /** The viewer's colour button (a highlight's, the next highlight's or the default colour). */
        private const val HIGHLIGHT_COLOR_PREFIX = "incremento_pdf_hl_color:"

        /** Marks the page while upstream's control panel is hidden. */
        private const val COMPACT_CLASS = "ankrimento-compact"
        private const val COMPACT_STYLE_JS =
            "(function () { if (document.getElementById('ankrimento-style')) return;" +
                " var style = document.createElement('style'); style.id = 'ankrimento-style';" +
                " style.textContent = 'body.$COMPACT_CLASS #pdf-controls { display: none !important; }' +" +
                // A highlight's own buttons (colour, note, resize, remove) sized for a finger: 32px, each
                // reaching 48px around it, spaced apart and lifted clear of the highlighted words.
                // The viewer fades the buttons, not their row, so each carries its own dark ring against the text.
                " ' .incremento-pdf-highlight-actions { gap: 16px !important; transform: translateY(-30px); }' +" +
                " ' .incremento-pdf-highlight-actions > button { box-shadow: 0 0 0 5px rgba(32, 33, 36, 0.96)," +
                " 0 2px 8px 5px rgba(0, 0, 0, 0.45) !important; }' +" +
                " ' .incremento-pdf-highlight-actions > button:not(.incremento-pdf-color-action)" +
                " { background: #3c4043 !important; color: #e8eaed !important; }' +" +
                " ' .incremento-pdf-highlight-actions > button { width: 32px !important; height: 32px !important;" +
                " font-size: 18px !important; line-height: 32px !important; position: relative; }' +" +
                " ' .incremento-pdf-highlight-actions > button::after { content: \"\"; position: absolute; inset: -8px; }' +" +
                " ' .incremento-pdf-highlight-actions > button svg { width: 20px !important; height: 20px !important; }';" +
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

        private const val EXTRA_LINK_FROM = "link_from"
        private const val EXTRA_LINK_PAGE = "link_page"
        private const val EXTRA_LINK_Y = "link_y"
        private const val SCROLL_PREFIX = "incremento_pdf_scroll:"

        /** What the viewer saves for its document: dropped while it only chooses a link's destination. */
        private val DESTINATION_KEEPS_NOTHING =
            listOf(
                "incremento_pdf_nav:",
                "incremento_pdf_zoom:",
                SCROLL_PREFIX,
                "incremento_pdf_appearance:",
                "incremento_pdf_hl_",
                "incremento_pdf_mark_read:",
                "incremento_pdf_bookmark_",
                "incremento_pdf_limit_override:",
                "incremento_pdf_finished:",
                "incremento_fill_field:",
                "incremento_pdf_page_links:",
            )

        /** [targetCardId]'s PDF, opened to choose where [sourcePage] of [sourceCardId] links to. */
        fun destinationIntent(
            context: Context,
            targetCardId: Long,
            sourceCardId: Long,
            sourcePage: Int,
            sourceY: Double?,
        ): Intent =
            intent(context, targetCardId)
                .putExtra(EXTRA_LINK_FROM, sourceCardId)
                .putExtra(EXTRA_LINK_PAGE, sourcePage)
                .putExtra(EXTRA_LINK_Y, sourceY ?: -1.0)
    }
}
