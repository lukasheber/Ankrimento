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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ichi2.anki.CollectionManager.withCol
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
 */
class PdfReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var viewerControlsShown = false
    private var page = 0
    private var pages = 0
    private var zoom = 1.0
    private var appearance = "original"

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
        lifecycleScope.launch {
            for (message in messages) {
                followViewer(message)
                val script = runCatching { withCol { Ankrimento.pdfMessage(cardId, message) } }
                script
                    .onSuccess { if (it.isNotEmpty()) webView.evaluateJavascript(it, null) }
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
                ReaderTool(getString(R.string.ankrimento_pdf_find), IncrementoIcons.Search) {
                    // The find box is part of upstream's panel.
                    showViewerControls(true)
                    webView.evaluateJavascript("window.incrementoPdfOpenFind && window.incrementoPdfOpenFind();", null)
                },
                tool(
                    R.string.ankrimento_pdf_mark_read,
                    IncrementoIcons.Bookmark,
                    "window.incrementoPdfMarkRead && window.incrementoPdfMarkRead();",
                ),
                tool(R.string.ankrimento_extract_selection, null, EXTRACT_JS),
                ReaderTool(getString(if (show) R.string.ankrimento_pdf_hide_controls else R.string.ankrimento_pdf_show_controls)) {
                    showViewerControls(!viewerControlsShown)
                },
            )
    }

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
                    // Upstream's zoom is relative to the page width; 100% fits it.
                    val steps = ((1.0 - zoom) / ZOOM_STEP).let { kotlin.math.round(it).toInt() }
                    if (steps != 0) webView.evaluateJavascript("window.incrementoPdfZoom && window.incrementoPdfZoom($steps);", null)
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
