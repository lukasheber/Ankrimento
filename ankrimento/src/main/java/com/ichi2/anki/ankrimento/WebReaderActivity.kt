// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

/**
 * Incremento's web reader: a web topic's page with desktop's web bridge, which
 * saves where reading stopped and restores it. Extract offers the selection
 * as a new card, as desktop's web dock does; it is on the text-selection menu.
 *
 * Back first returns through the links followed in the page, as in a
 * browser, then leaves the reader.
 */
class WebReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private var nonce = ""

    /** Bridge messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    private val backInPage =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = webView.goBack()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            ReaderWebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            val prefix = "$MESSAGE_PREFIX$nonce:"
                            val text = message.message()
                            if (nonce.isEmpty() || !text.startsWith(prefix)) return false
                            messages.trySend(text.removePrefix(prefix))
                            return true
                        }
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            backInPage.isEnabled = view.canGoBack()
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            lifecycleScope.launch {
                                val script = withCol { Ankrimento.webScript(cardId, nonce, url) }
                                webView.evaluateJavascript(script, null)
                            }
                        }
                    }
            }
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), SELECTION_JS) { result ->
                    extract(decodeJsString(result))
                },
            )
        setReaderContent(webView)
        onBackPressedDispatcher.addCallback(this, backInPage)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_web_back), IncrementoIcons.Previous) { if (webView.canGoBack()) webView.goBack() },
                ReaderTool(getString(R.string.ankrimento_extract_selection)) { extractSelection() },
            )
        lifecycleScope.launch {
            val (url, bridgeNonce) = withCol { Ankrimento.webOpen(cardId) }
            nonce = bridgeNonce
            // A web extract's link opens the page it came from, as desktop's open_web_location.
            val asked = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            webView.loadUrl(asked ?: url)
        }
        Ankrimento.readerScope.launch {
            for (message in messages) {
                runCatching { withCol { Ankrimento.webMessage(cardId, message) } }
                    .onFailure { Timber.w(it, "Web bridge message failed") }
            }
        }
    }

    /** Upstream's web bridge reports the position when the page is hidden. */
    override fun reportPosition() = webView.evaluateJavascript("window.dispatchEvent(new Event('pagehide'));", null)

    private fun extractSelection() {
        webView.evaluateJavascript(SELECTION_JS) { result -> extract(decodeJsString(result)) }
    }

    private fun extract(text: String) {
        // The page's URL (read here: WebView only answers on the main thread): the extract links back to it, as desktop's do.
        val url = webView.url.orEmpty()
        lifecycleScope.launch {
            val started = withCol { Ankrimento.webExtract(cardId, text, url) }
            if (!started) Toast.makeText(this@WebReaderActivity, R.string.ankrimento_web_select_first, Toast.LENGTH_SHORT).show()
        }
    }

    /** evaluateJavascript returns a JSON-encoded string. */
    private fun decodeJsString(result: String): String = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")

    override fun onDestroy() {
        messages.close()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SELECTION_JS = "String(window.getSelection ? window.getSelection() : '')"

        /** Upstream's web dock _PYCMD_BRIDGE; each message is prefix + nonce + ":" + message. */
        private const val MESSAGE_PREFIX = "__incremento_webdock_pycmd__:"

        const val EXTRA_URL = "url"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "web", cardId, session = false)!!

        /** The web topic opened at [url] (a page it led to), instead of where reading stopped. */
        fun intentAt(
            context: Context,
            cardId: Long,
            url: String,
        ): Intent = intent(context, cardId).putExtra(EXTRA_URL, url)
    }
}
