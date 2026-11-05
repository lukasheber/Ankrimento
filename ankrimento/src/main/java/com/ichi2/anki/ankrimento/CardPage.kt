// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.stripAvRefs
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

/**
 * A card shown as a page in Incremento's readers: the HTML its template
 * renders, with the note type's CSS, and images from the collection's media.
 */
object CardPage {
    /** Where pages are loaded from; media requests are under it. */
    const val BASE_URL = "https://appassets.androidplatform.net/media/"

    private const val OCCLUSION = "image-occlusion"

    /** Anki's review styles: play buttons, the typed comparison's colours, image occlusion. */
    private const val REVIEWER_CSS = "<link rel=\"stylesheet\" href=\"/_anki/css/reviewer.css\">"

    /** Draws image occlusion: the note type's template calls `anki.imageOcclusion.setup()`. */
    private const val REVIEWER_JS = "<script src=\"/_anki/js/reviewer_extras_bundle.js\"></script>"

    /** Anki's math delimiters. */
    private val MATH = listOf("\\(", "\\[")

    /**
     * AnkiDroid's MathJax, as its card pages load it (`AndroidCardRenderContext`), served by
     * [ankiAssets]. Its config leaves typesetting to the page, so the page typesets once loaded.
     */
    private const val MATHJAX =
        "<link rel=\"stylesheet\" href=\"/_anki/mathjax.css\">" +
            "<script src=\"/_anki/js/mathjax.js\"></script>" +
            "<script src=\"/_anki/js/vendor/mathjax/tex-chtml-full.js\"></script>" +
            "<script>MathJax.startup.promise.then(function () { return MathJax.typesetPromise(); });</script>"

    /** Desktop's `pycmd` for links in cards (a video extract's "▶ 0:19"), answered by [Links]. */
    private const val PYCMD =
        "window.pycmd = function (message) { if (window.AnkrimentoCard) AnkrimentoCard.pycmd(String(message)); return false; };"

    /**
     * What a card's links ask through `pycmd`, as desktop's reviewer answers it:
     * an extract's link opens its source there ([SourceLinks]).
     */
    class Links(
        private val activity: android.app.Activity,
    ) {
        @android.webkit.JavascriptInterface
        fun pycmd(message: String) {
            val intent = SourceLinks.intentFor(activity, message) ?: return
            activity.runOnUiThread { activity.startActivity(intent) }
        }
    }

    /** The page for [body] (its sound and video left out: a topic is read as text), with [head] added to its head. */
    fun html(
        css: String,
        body: String,
        head: String = "",
    ): String {
        val occlusion = OCCLUSION in body
        val reviewerStyles = occlusion || "replay-button" in body || "id=\"typeans\"" in body || "id=typeans" in body
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            (if (reviewerStyles) REVIEWER_CSS else "") +
            "<style>$css</style><script>$PYCMD</script>" +
            (if (occlusion) REVIEWER_JS else "") +
            (if (MATH.any { it in body }) MATHJAX else "") +
            head +
            "</head>" +
            "<body class=\"card\"><div id=\"qa\">${stripAvRefs(body)}</div></body></html>"
    }

    /**
     * AnkiDroid's own web assets under `/_anki/` (as its `ViewerResourceHandler` serves them):
     * MathJax and its parts at `js/…`, its stylesheet at `mathjax.css`, Anki's review styles at `css/…`.
     */
    fun ankiAssets(context: Context): (String) -> WebResourceResponse? {
        val assets = context.applicationContext.assets
        return { path ->
            val asset =
                when {
                    path == "mathjax.css" -> "mathjax/mathjax.css"
                    (path.startsWith("js/") || path.startsWith("css/")) && ".." !in path -> "backend/$path"
                    else -> null
                }
            asset?.let {
                runCatching {
                    WebResourceResponse(
                        URLConnection.guessContentTypeFromName(it) ?: mimeFor(it),
                        null,
                        assets.open(it),
                    )
                }.getOrNull()
            }
        }
    }

    private fun mimeFor(path: String): String =
        when (path.substringAfterLast('.')) {
            "js" -> "text/javascript"
            "css" -> "text/css"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }

    /** A file of the collection's media, never outside its folder. */
    fun mediaFile(
        media: File,
        path: String,
    ): WebResourceResponse? {
        val file = File(media, path).canonicalFile
        if (!file.path.startsWith(media.path + File.separator) || !file.isFile) return null
        return WebResourceResponse(URLConnection.guessContentTypeFromName(file.name), null, FileInputStream(file))
    }

    /** Text size and page colour; the colours are upstream's EPUB appearance modes. */
    fun pageScript(
        scale: Double,
        appearance: String,
        alignStart: Boolean,
    ): String {
        val options = JSONObject().put("scale", scale).put("mode", appearance).put("start", alignStart)
        return "(function (o) {" +
            " var style = document.getElementById('ankrimento-page');" +
            " if (!style) { style = document.createElement('style'); style.id = 'ankrimento-page'; document.head.appendChild(style); }" +
            " var css = 'html { font-size: ' + Math.round(o.scale * 100) + '% !important; }';" +
            // A topic reads like a page; flashcard templates usually centre their text.
            " if (o.start) css += ' body, .card { text-align: start !important; }';" +
            " if (o.mode !== 'original') { var bg = o.mode === 'night' ? '#15120f' : '#1e1e1e';" +
            " var fg = o.mode === 'night' ? '#d9c7a4' : '#dedede';" +
            " css += ' html, body, .card { background: ' + bg + ' !important; color: ' + fg + ' !important; }'" +
            " + ' a { color: #8ab4f8 !important; }'; }" +
            " style.textContent = css; })($options);"
    }

    /**
     * Which reader shows [cardId]: a document's or "text" (from [Ankrimento.cardReader]),
     * "item" for an item in the Incremento session deck, or "" for the reviewer: elsewhere
     * AnkiDroid reviews as usual, as it does when the app supplies no [AnkrimentoApp.cardDisplay].
     */
    @WorkerThread
    fun readerFor(
        col: Collection,
        cardId: Long,
    ): String {
        val reader = Ankrimento.cardReader(cardId)
        if (reader.isNotEmpty()) return reader
        val card = col.getCard(cardId)
        val sessionDeck = col.decks.idForName(Ankrimento.sessionDeckName()) ?: return ""
        return if (card.did == sessionDeck && AnkrimentoApp.cardDisplay != null) "item" else ""
    }
}
