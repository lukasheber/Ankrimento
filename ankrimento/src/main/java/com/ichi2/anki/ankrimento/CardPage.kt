// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection
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

    /** Audio and video placeholders; readers show text only (see [canShow]). */
    private val AV_TAG = Regex("""\[anki:play:[^\]]*]""")

    /** What only AnkiDroid's reviewer shows properly: typed answers, math, image occlusion. */
    private val NEEDS_REVIEWER = listOf("[[type:", "\\(", "\\[", "image-occlusion", "<canvas")

    fun html(
        css: String,
        body: String,
    ): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$css</style></head><body class=\"card\"><div id=\"qa\">${body.replace(AV_TAG, "")}</div></body></html>"

    /**
     * Whether a reader shows [card] as faithfully as the reviewer: no sound or
     * video to play, no answer to type, no math or image occlusion to draw.
     */
    @WorkerThread
    fun canShow(
        col: Collection,
        card: Card,
    ): Boolean {
        val output = card.renderOutput(col)
        if (output.questionAvTags.isNotEmpty() || output.answerAvTags.isNotEmpty()) return false
        val text = output.questionText + output.answerText
        return NEEDS_REVIEWER.none { it in text }
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
            " if (o.mode !== 'original') { var bg = o.mode === 'night' ? '#15120f' : '#1e1e1e'; var fg = o.mode === 'night' ? '#d9c7a4' : '#dedede';" +
            " css += ' html, body, .card { background: ' + bg + ' !important; color: ' + fg + ' !important; }'" +
            " + ' a { color: #8ab4f8 !important; }'; }" +
            " style.textContent = css; })($options);"
    }

    /**
     * Which reader shows [cardId]: a document's or "text" (from [Ankrimento.cardReader]),
     * "item" for a card a session's item reader can show, or "" for the reviewer.
     * Items use the reader only in the Incremento session deck: elsewhere AnkiDroid reviews as usual.
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
        return if (card.did == sessionDeck && canShow(col, card)) "item" else ""
    }
}
