// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ichi2.anki.CollectionManager.withCol
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
 */
class PdfReaderActivity : ComponentActivity() {
    private val cardId by lazy { intent.getLongExtra(EXTRA_CARD_ID, 0L) }
    private lateinit var webView: WebView

    /** Viewer messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
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
                val script = runCatching { withCol { Ankrimento.pdfMessage(cardId, message) } }
                script
                    .onSuccess { if (it.isNotEmpty()) webView.evaluateJavascript(it, null) }
                    .onFailure { Timber.w(it, "PDF viewer message failed: %s", message.substringBefore(':')) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect(::onEvent)
            }
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
        }
    }

    private fun onEvent(event: Ankrimento.Event) {
        when (event) {
            is Ankrimento.Event.CloseReader -> if (event.cardId == cardId) finish()
            is Ankrimento.Event.OpenUrl -> openUrl(event.url)
            is Ankrimento.Event.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is Ankrimento.Event.ExtractDraft -> if (event.sourceCardId == cardId) showExtractDialog()
            Ankrimento.Event.EnterReview -> Unit
        }
    }

    private fun showExtractDialog() {
        ExtractDialog.showFor(this, cardId) { script ->
            if (script.isNotEmpty()) webView.evaluateJavascript(script, null)
        }
    }

    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser for %s", url)
        }
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
        private const val EXTRA_CARD_ID = "card_id"
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val VIEWER_URL = "$ORIGIN/assets/ankrimento/web/pdf_dock.html"
        private const val WORKER_URL = "$ORIGIN/assets/ankrimento/web/pdfjs/pdf.worker.min.js"
        private const val PDF_URL = "$ORIGIN/ankrimento-pdf/document.pdf"

        /** Desktop's `pycmd`, defined before the viewer's scripts run. */
        private const val PYCMD_SCRIPT =
            "window.pycmd = function (message) { AnkrimentoPdfHost.pycmd(String(message)); return false; };"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = Intent(context, PdfReaderActivity::class.java).putExtra(EXTRA_CARD_ID, cardId)
    }
}
