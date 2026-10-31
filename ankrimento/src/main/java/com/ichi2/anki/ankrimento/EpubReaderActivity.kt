// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Incremento's EPUB reader: each extracted chapter in a WebView with desktop's
 * EPUB page script, whose console messages Incremento's backend handles.
 * The top bar and the text-selection menu call the page script's own
 * actions, as desktop's toolbar does.
 */
class EpubReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var section: Ankrimento.EpubSection? = null
    private var pendingScrollRatio: Double? = null

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
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_epub_previous), IncrementoIcons.Previous) { turnChapter(-1) },
                ReaderTool(getString(R.string.ankrimento_epub_next), IncrementoIcons.Next) { turnChapter(1) },
                ReaderTool(getString(R.string.ankrimento_epub_read_here), IncrementoIcons.Bookmark) { runScript(READ_MARKER_JS) },
                ReaderTool(getString(R.string.ankrimento_extract_selection)) { runScript(EXTRACT_JS) },
                ReaderTool(getString(R.string.ankrimento_highlight_selection)) { runScript(HIGHLIGHT_JS) },
            )
        lifecycleScope.launch { load(null, null) }
        lifecycleScope.launch {
            for (message in messages) {
                val current = section ?: continue
                runCatching { withCol { Ankrimento.epubMessage(cardId, current.index, message) } }
                    .onSuccess { target -> target?.let { (index, ratio) -> load(index, ratio) } }
                    .onFailure { Timber.w(it, "EPUB page message failed: %s", message.substringBefore(':')) }
            }
        }
    }

    /** The previous chapter opens at its end, the next at its start. */
    private fun turnChapter(delta: Int) {
        val current = section ?: return
        lifecycleScope.launch { load(current.index + delta, if (delta < 0) 1.0 else 0.0) }
    }

    private fun runScript(script: String) = webView.evaluateJavascript(script, null)

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
        subtitle = getString(R.string.ankrimento_epub_chapter, opened.index + 1, opened.count)
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
                        val script = withCol { Ankrimento.epubScript(cardId, current, pendingScrollRatio) }
                        webView.evaluateJavascript(TOUCH_SELECTION_JS, null)
                        webView.evaluateJavascript(script, null)
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
        private const val ORIGIN_HOST = "appassets.androidplatform.net"

        /** Upstream's _PYCMD_BRIDGE; each message is prefix + nonce + ":" + message. */
        private const val MESSAGE_PREFIX = "__incremento_epub__:"

        /**
         * Upstream selects text by dragging a mouse and blocks native selection meanwhile;
         * a finger's drag scrolls instead. Touches on the text keep Android's long-press
         * selection (handles, and the Extract action); upstream's own controls still get theirs.
         */
        private const val TOUCH_SELECTION_JS =
            "window.addEventListener('pointerdown', function (event) {" +
                " var target = event.target; if (event.pointerType !== 'touch' || !target || !target.closest) return;" +
                " if (target.closest('[class*=\"incremento-epub-\"]:not(.incremento-epub-highlight):not(.incremento-epub-scaled)')) return;" +
                " event.stopPropagation(); }, true);"

        private const val EXTRACT_JS = "window.incrementoFillEpubField && window.incrementoFillEpubField(0);"
        private const val HIGHLIGHT_JS = "window.incrementoAddEpubHighlight && window.incrementoAddEpubHighlight();"
        private const val READ_MARKER_JS = "window.incrementoToggleEpubReadMarker && window.incrementoToggleEpubReadMarker();"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "epub", cardId, session = false)!!
    }
}
