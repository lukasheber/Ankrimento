// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.ReadingSettingsSheet
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

/**
 * A topic without a document: its note, as its card template renders it,
 * read like a page of a book. There is nothing to reveal, so no "Show
 * answer"; the review bar ends it like any other topic. Extract is on the
 * text-selection menu, as in the document readers.
 *
 * Text size and page colour are this device's (desktop keeps no such setting
 * for an ordinary topic); typeface, spacing and margins are shared with the
 * EPUB reader.
 */
class TextReaderActivity : ReaderActivity() {
    private lateinit var webView: ReaderWebView
    private lateinit var preferences: ReadingPreferences
    private var textScale = 1.0
    private var appearance = "original"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        textScale = stored.getFloat(KEY_SCALE, 1f).toDouble()
        appearance = stored.getString(KEY_APPEARANCE, null) ?: "original"
        preferences = ReadingPreferences.load(this)
        webView =
            ReaderWebView(this).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
            }
        webView.selectionActions =
            listOf(
                ReaderWebView.SelectionAction(getString(R.string.ankrimento_epub_extract), SELECTION_JS) { result ->
                    extract(decodeJsString(result))
                },
            )
        setReaderContent(webView)
        toggleBarsOnTap(webView)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_reading_settings), IncrementoIcons.TextFormat, ::showReadingSettings),
                ReaderTool(getString(R.string.ankrimento_extract_selection)) {
                    webView.evaluateJavascript(SELECTION_JS) { extract(decodeJsString(it)) }
                },
            )
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val (html, mediaDir) =
            runCatching {
                withCol {
                    val output = getCard(cardId).renderOutput(this, reload = true)
                    page(output.css, output.answerText) to media.dir
                }
            }.getOrElse { error ->
                Timber.w(error, "Rendering a topic failed")
                Toast.makeText(this, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        val media = mediaDir.canonicalFile
        val assetLoader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/media/") { path -> mediaFile(media, path) }
                .build()
        webView.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                // Links in a topic open in the browser, not in place of the topic.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    openUrl(request.url.toString())
                    return true
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) = applyReading()
            }
        webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    /** A file of the collection's media, never outside its folder. */
    private fun mediaFile(
        media: File,
        path: String,
    ): WebResourceResponse? {
        val file = File(media, path).canonicalFile
        if (!file.path.startsWith(media.path + File.separator) || !file.isFile) return null
        return WebResourceResponse(URLConnection.guessContentTypeFromName(file.name), null, FileInputStream(file))
    }

    private fun applyReading() {
        webView.evaluateJavascript(preferences.script(), null)
        webView.evaluateJavascript(pageScript(textScale, appearance), null)
    }

    private fun showReadingSettings() {
        sheet = {
            ReadingSettingsSheet(
                textScale = textScale,
                appearance = appearance,
                preferences = preferences,
                onTextScale = { scale ->
                    textScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
                    store()
                    showReadingSettings()
                },
                onAppearance = { mode ->
                    appearance = mode
                    store()
                    showReadingSettings()
                },
                onPreferences = { chosen ->
                    preferences = chosen
                    chosen.save(this)
                    applyReading()
                    showReadingSettings()
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun store() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
            putFloat(KEY_SCALE, textScale.toFloat())
            putString(KEY_APPEARANCE, appearance)
        }
        applyReading()
    }

    private fun extract(text: String) {
        lifecycleScope.launch {
            // Upstream's extraction from any card: the extract joins the topic in the knowledge tree.
            val started = withCol { Ankrimento.webExtract(cardId, text) }
            if (!started) Toast.makeText(this@TextReaderActivity, R.string.ankrimento_web_select_first, Toast.LENGTH_SHORT).show()
        }
    }

    /** evaluateJavascript returns a JSON-encoded string. */
    private fun decodeJsString(result: String): String = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")

    companion object {
        private const val BASE_URL = "https://appassets.androidplatform.net/media/"
        private const val SELECTION_JS = "String(window.getSelection ? window.getSelection() : '')"
        private const val PREFERENCES = "ankrimento_text_reader"
        private const val KEY_SCALE = "text_scale"
        private const val KEY_APPEARANCE = "appearance"

        /** The EPUB reader's limits (upstream's). */
        private const val MIN_SCALE = 0.7
        private const val MAX_SCALE = 2.2

        /** Audio and video play in the reviewer; a topic shows their text only. */
        private val AV_TAG = Regex("""\[anki:play:[^\]]*]""")

        private fun page(
            css: String,
            body: String,
        ): String =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>$css</style></head><body class=\"card\"><div id=\"qa\">${body.replace(AV_TAG, "")}</div></body></html>"

        /** Text size and page colour; the colours are upstream's EPUB appearance modes. */
        private fun pageScript(
            scale: Double,
            appearance: String,
        ): String {
            val options = JSONObject().put("scale", scale).put("mode", appearance)
            return "(function (o) {" +
                " var style = document.getElementById('ankrimento-page');" +
                " if (!style) { style = document.createElement('style'); style.id = 'ankrimento-page'; document.head.appendChild(style); }" +
                " var css = 'html { font-size: ' + Math.round(o.scale * 100) + '% !important; } body, .card { text-align: start !important; }';" +
                " if (o.mode !== 'original') { var bg = o.mode === 'night' ? '#15120f' : '#1e1e1e'; var fg = o.mode === 'night' ? '#d9c7a4' : '#dedede';" +
                " css += ' html, body, .card { background: ' + bg + ' !important; color: ' + fg + ' !important; }'" +
                " + ' a { color: #8ab4f8 !important; }'; }" +
                " style.textContent = css; })($options);"
        }

        fun intent(
            context: Context,
            cardId: Long,
        ) = ReaderActivity.intent(context, "text", cardId, session = false)!!
    }
}
