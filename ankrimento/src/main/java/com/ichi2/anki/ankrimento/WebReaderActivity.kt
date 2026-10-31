// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

/**
 * Incremento's web reader: a web topic's page with desktop's web bridge, which
 * saves where reading stopped and restores it. Extract offers the selection
 * as a new card, as desktop's web dock does.
 */
class WebReaderActivity : ComponentActivity() {
    private val cardId by lazy { intent.getLongExtra(EXTRA_CARD_ID, 0L) }
    private lateinit var webView: WebView
    private var nonce = ""

    /** Bridge messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            WebView(this).apply {
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
        val toolbar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Button(context).apply {
                        setText(R.string.ankrimento_web_back)
                        setOnClickListener { if (webView.canGoBack()) webView.goBack() }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.ankrimento_epub_extract)
                        setOnClickListener { extractSelection() }
                    },
                )
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(toolbar)
            },
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            },
        )
        lifecycleScope.launch {
            val (url, bridgeNonce) = withCol { Ankrimento.webOpen(cardId) }
            nonce = bridgeNonce
            webView.loadUrl(url)
        }
        lifecycleScope.launch {
            for (message in messages) {
                runCatching { withCol { Ankrimento.webMessage(cardId, message) } }
                    .onFailure { Timber.w(it, "Web bridge message failed") }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect { event ->
                    when (event) {
                        is Ankrimento.Event.ExtractDraft ->
                            if (event.sourceCardId ==
                                cardId
                            ) {
                                ExtractDialog.showFor(this@WebReaderActivity, cardId) { }
                            }
                        is Ankrimento.Event.Message -> Toast.makeText(this@WebReaderActivity, event.text, Toast.LENGTH_LONG).show()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun extractSelection() {
        webView.evaluateJavascript("String(window.getSelection ? window.getSelection() : '')") { result ->
            // evaluateJavascript returns a JSON-encoded string.
            val text = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")
            lifecycleScope.launch {
                val started = withCol { Ankrimento.webExtract(cardId, text) }
                if (!started) Toast.makeText(this@WebReaderActivity, R.string.ankrimento_web_select_first, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        messages.close()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CARD_ID = "card_id"

        /** Upstream's web dock _PYCMD_BRIDGE; each message is prefix + nonce + ":" + message. */
        private const val MESSAGE_PREFIX = "__incremento_webdock_pycmd__:"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = Intent(context, WebReaderActivity::class.java).putExtra(EXTRA_CARD_ID, cardId)
    }
}
