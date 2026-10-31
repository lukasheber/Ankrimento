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
import com.ichi2.anki.ankrimento.ui.ReaderTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
 * The top bar calls the viewer's own controls (find, page turns, zoom, read
 * mark), and Extract is on the text-selection menu.
 */
class PdfReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var viewerControlsShown = false

    /** Viewer messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = ReaderWebView(this)
        webView.selectionActions =
            listOf(ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), EXTRACT_JS))
        setReaderContent(webView)
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
                showPage(message)
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
                ReaderTool(getString(R.string.ankrimento_pdf_find), IncrementoIcons.Search) {
                    // The find box is part of upstream's panel.
                    showViewerControls(true)
                    webView.evaluateJavascript("window.incrementoPdfOpenFind && window.incrementoPdfOpenFind();", null)
                },
                tool(R.string.ankrimento_pdf_previous, IncrementoIcons.Previous, "window.incrementoPdfNav && window.incrementoPdfNav(-1);"),
                tool(R.string.ankrimento_pdf_next, IncrementoIcons.Next, "window.incrementoPdfNav && window.incrementoPdfNav(1);"),
                tool(R.string.ankrimento_extract_selection, null, EXTRACT_JS),
                tool(
                    R.string.ankrimento_pdf_mark_read,
                    IncrementoIcons.Bookmark,
                    "window.incrementoPdfMarkRead && window.incrementoPdfMarkRead();",
                ),
                tool(R.string.ankrimento_zoom_in, null, "window.incrementoPdfZoom && window.incrementoPdfZoom(1);"),
                tool(R.string.ankrimento_zoom_out, null, "window.incrementoPdfZoom && window.incrementoPdfZoom(-1);"),
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

    /** Upstream reports page turns as `incremento_pdf_nav:<card>:<page>`. */
    private fun showPage(message: String) {
        val parts = message.split(':')
        if (parts.size != 3 || parts[0] != "incremento_pdf_nav") return
        parts[2].toIntOrNull()?.let { subtitle = getString(R.string.ankrimento_pdf_page, it) }
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
            showViewerControls(viewerControlsShown)
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

        /** Desktop's first extract shortcut on the selection. */
        private const val EXTRACT_JS = "window.incrementoHandleExtractShortcut && window.incrementoHandleExtractShortcut(0);"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "pdf", cardId, session = false)!!
    }
}
