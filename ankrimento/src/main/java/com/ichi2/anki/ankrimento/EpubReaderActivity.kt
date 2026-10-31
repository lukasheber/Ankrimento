// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Incremento's EPUB reader: each extracted chapter in a WebView with desktop's
 * EPUB page script, whose console messages Incremento's backend handles.
 * The toolbar calls the page script's own actions, as desktop's toolbar does.
 */
class EpubReaderActivity : ComponentActivity() {
    private val cardId by lazy { intent.getLongExtra(EXTRA_CARD_ID, 0L) }
    private lateinit var webView: WebView
    private var section: Ankrimento.EpubSection? = null
    private var pendingScrollRatio: Double? = null

    /** Page-script messages, applied one at a time in the order sent. */
    private val messages = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView =
            WebView(this).apply {
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
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(toolbar())
            },
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect(::onEvent)
            }
        }
    }

    private fun toolbar(): HorizontalScrollView {
        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

        fun button(
            label: Int,
            action: () -> Unit,
        ) = buttons.addView(
            Button(this).apply {
                setText(label)
                setOnClickListener { action() }
            },
        )
        button(R.string.ankrimento_epub_previous) { section?.let { lifecycleScope.launch { load(it.index - 1, 1.0) } } }
        button(R.string.ankrimento_epub_extract) {
            webView.evaluateJavascript("window.incrementoFillEpubField && window.incrementoFillEpubField(0);", null)
        }
        button(R.string.ankrimento_epub_highlight) {
            webView.evaluateJavascript("window.incrementoAddEpubHighlight && window.incrementoAddEpubHighlight();", null)
        }
        button(R.string.ankrimento_epub_read_here) {
            webView.evaluateJavascript("window.incrementoToggleEpubReadMarker && window.incrementoToggleEpubReadMarker();", null)
        }
        button(R.string.ankrimento_epub_next) { section?.let { lifecycleScope.launch { load(it.index + 1, 0.0) } } }
        return HorizontalScrollView(this).apply { addView(buttons) }
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
                        webView.evaluateJavascript(script, null)
                    }
                }
            }
        val relative = File(opened.path).canonicalFile.relativeTo(root).invariantSeparatorsPath
        webView.loadUrl("https://$ORIGIN_HOST/epub/" + relative.split('/').joinToString("/") { Uri.encode(it) })
    }

    private fun onEvent(event: Ankrimento.Event) {
        when (event) {
            is Ankrimento.Event.ExtractDraft -> if (event.sourceCardId == cardId) ExtractDialog.showFor(this, cardId) { }
            is Ankrimento.Event.OpenUrl -> openUrl(event.url)
            is Ankrimento.Event.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is Ankrimento.Event.CloseReader -> if (event.cardId == cardId) finish()
            Ankrimento.Event.EnterReview -> Unit
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser for %s", url)
        }
    }

    override fun onDestroy() {
        messages.close()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CARD_ID = "card_id"
        private const val ORIGIN_HOST = "appassets.androidplatform.net"

        /** Upstream's _PYCMD_BRIDGE; each message is prefix + nonce + ":" + message. */
        private const val MESSAGE_PREFIX = "__incremento_epub__:"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = Intent(context, EpubReaderActivity::class.java).putExtra(EXTRA_CARD_ID, cardId)
    }
}
