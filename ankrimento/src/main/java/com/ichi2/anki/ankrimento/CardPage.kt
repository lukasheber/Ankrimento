// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import com.ichi2.anki.libanki.AvRef
import com.ichi2.anki.libanki.AvTag
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Sound
import com.ichi2.anki.libanki.SoundOrVideoTag
import com.ichi2.anki.libanki.TTSTag
import com.ichi2.anki.libanki.stripAvRefs
import org.json.JSONArray
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

    /** `[[type:Field]]`, as AnkiDroid's `TypeAnswer.PATTERN`. */
    private val TYPE_TAG = Regex("""\[\[type:(.+?)]]""")

    private const val OCCLUSION = "image-occlusion"

    /** Anki's review styles: replay buttons, the typed comparison's colours, image occlusion. */
    private const val REVIEWER_CSS = "<link rel=\"stylesheet\" href=\"/_anki/css/reviewer.css\">"

    /** Draws image occlusion: the note type's template calls `anki.imageOcclusion.setup()`. */
    private const val REVIEWER_JS = "<script src=\"/_anki/js/reviewer_extras_bundle.js\"></script>"

    /** Plays a side's sound in order, as the reviewer's player does; `ankrimentoPlay` is a replay button's. */
    private const val PLAYER = """
(function (media) {
  var current = null, queue = [];
  function next() {
    var ref = queue.shift();
    current = null;
    if (!ref) return;
    var player = document.querySelector('video[data-play="' + ref + '"]') || new Audio(media.urls[ref]);
    current = player;
    player.onended = next;
    player.onerror = next;
    var started = player.play();
    if (started) started.catch(next);
  }
  window.ankrimentoStop = function () {
    queue = [];
    if (current) { current.onended = null; current.onerror = null; }
    if (current) current.pause();
    current = null;
    document.querySelectorAll('video').forEach(function (video) { video.pause(); });
  };
  window.ankrimentoPlay = function (ref) { window.ankrimentoStop(); queue = [ref]; next(); return false; };
  window.addEventListener('load', function () { queue = media.autoplay.slice(); next(); });
})"""

    /** AnkiDroid's replay button (`expandSounds`). */
    private fun replayButton(ref: String) =
        "<a class=\"replay-button soundLink\" href=\"#\" onclick=\"return ankrimentoPlay('$ref')\"><span>" +
            "<svg class=\"playImage\" viewBox=\"0 0 64 64\" version=\"1.1\"><circle cx=\"32\" cy=\"32\" r=\"29\"/>" +
            "<path d=\"M56.502,32.301l-37.502,20.101l0.329,-40.804l37.173,20.703Z\"/></svg></span></a>"

    /**
     * What a side of an item plays: its `[anki:play:…]` references, the ones it plays by
     * itself when shown ([autoplay], as `q:0`), and whether references show play buttons.
     */
    class SideMedia private constructor(
        private val question: List<AvTag>,
        private val answer: List<AvTag>,
        val autoplay: List<String>,
        private val showButtons: Boolean,
    ) {
        private fun tag(ref: AvRef) = (if (ref.side == "q") question else answer).getOrNull(ref.index)

        private fun url(tag: SoundOrVideoTag) = BASE_URL + android.net.Uri.encode(tag.filename)

        /** Audio or video in a video container: the reader cannot tell them apart without decoding, a player shows both. */
        private fun isVideo(tag: SoundOrVideoTag) =
            tag.filename.substringAfterLast('.', "").lowercase().let {
                it in Sound.VIDEO_ONLY_EXTENSIONS || it in Sound.AUDIO_OR_VIDEO_EXTENSIONS
            }

        fun expand(body: String): String =
            AvRef.REGEX.replace(body) { match ->
                val ref = AvRef.from(match) ?: return@replace ""
                val tag = tag(ref) as? SoundOrVideoTag ?: return@replace ""
                val name = "${ref.side}:${ref.index}"
                when {
                    isVideo(tag) -> "<video src=\"${url(tag)}\" controls playsinline data-play=\"$name\" style=\"max-width: 100%\"></video>"
                    showButtons -> replayButton(name)
                    else -> ""
                }
            }

        fun script(): String {
            val urls = JSONObject()
            for ((side, tags) in listOf("q" to question, "a" to answer)) {
                tags.forEachIndexed { index, tag ->
                    if (tag is SoundOrVideoTag && !isVideo(tag)) urls.put("$side:$index", url(tag))
                }
            }
            val media = JSONObject().put("urls", urls).put("autoplay", JSONArray(autoplay))
            return "<script>$PLAYER(${media.toString().replace("</", "<\\/")});</script>"
        }

        companion object {
            /** [side] is "q" or "a". Desktop's `Reviewer._replay`: the question's sound again on the answer when the deck says so. */
            fun of(
                question: List<AvTag>,
                answer: List<AvTag>,
                side: String,
                autoplay: Boolean,
                replayQuestion: Boolean,
                showButtons: Boolean,
            ): SideMedia {
                fun refs(
                    name: String,
                    tags: List<AvTag>,
                ) = tags.indices.map { "$name:$it" }
                val played =
                    when {
                        !autoplay -> emptyList()
                        side == "q" -> refs("q", question)
                        replayQuestion -> refs("q", question) + refs("a", answer)
                        else -> refs("a", answer)
                    }
                return SideMedia(question, answer, played, showButtons)
            }
        }
    }

    /** A card's `[[type:…]]` tag: mirrors AnkiDroid's `TypeAnswerModifiers`, internal to its app module. */
    data class TypedTag(
        val field: String,
        val combining: Boolean,
        val cloze: Boolean,
        val noSuggest: Boolean,
    ) {
        companion object {
            fun find(text: String): TypedTag? {
                var remaining = TYPE_TAG.find(text)?.groupValues?.get(1) ?: return null
                var combining = true
                var cloze = false
                var noSuggest = false
                while (true) {
                    when {
                        remaining.startsWith("nosuggest:") -> noSuggest = true
                        remaining.startsWith("cloze:") -> cloze = true
                        remaining.startsWith("nc:") -> combining = false
                        else -> return TypedTag(remaining, combining, cloze, noSuggest)
                    }
                    remaining = remaining.substringAfter(':')
                }
            }
        }
    }

    /** An answer to type: the box on the question, the comparison on the answer, as AnkiDroid's `TypeAnswer`. */
    class TypedAnswer(
        val expected: String,
        val combining: Boolean,
        private val noSuggest: Boolean,
        private val font: String,
        private val fontSize: Int,
    ) {
        private val fontStyle = "font-family: '$font'; font-size: ${fontSize}px"

        fun onQuestion(text: String): String {
            val suggestions = if (noSuggest) "autocorrect=\"off\" autocapitalize=\"off\" spellcheck=\"false\" " else ""
            val box =
                "<center><input type=\"text\" id=\"typeans\" autocomplete=\"off\" $suggestions" +
                    "style=\"$fontStyle\" onkeydown=\"if (event.key === 'Enter') AnkrimentoItem.submit()\"></center>"
            return TYPE_TAG.replace(text) { box }
        }

        fun onAnswer(
            text: String,
            comparison: String,
        ): String = TYPE_TAG.replace(text) { "<div style=\"$fontStyle\">$comparison</div>" }

        companion object {
            /** The answer [text] asks for, or null when it asks for none or names no field of the card. */
            @WorkerThread
            fun of(
                col: Collection,
                card: Card,
                text: String,
            ): TypedAnswer? {
                val tag = TypedTag.find(text) ?: return null
                val field = card.noteType(col).fields.firstOrNull { it.name == tag.field } ?: return null
                val value = card.note(col).getItem(tag.field)
                val expected = if (tag.cloze) col.extractClozeForTyping(value, card.ord + 1) else value
                return TypedAnswer(expected, tag.combining, tag.noSuggest, field.font, field.fontSize)
            }
        }
    }

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

    /** The page for [body]; without [media] its sound and video are left out (a topic read as text). */
    fun html(
        css: String,
        body: String,
        media: SideMedia? = null,
    ): String {
        val occlusion = OCCLUSION in body
        val reviewerStyles = occlusion || media != null || "id=\"typeans\"" in body || "id=typeans" in body
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            (if (reviewerStyles) REVIEWER_CSS else "") +
            "<style>$css</style><script>$PYCMD</script>" +
            (if (occlusion) REVIEWER_JS else "") +
            (if (MATH.any { it in body }) MATHJAX else "") +
            (media?.script() ?: "") +
            "</head>" +
            "<body class=\"card\"><div id=\"qa\">${media?.expand(body) ?: stripAvRefs(body)}</div></body></html>"
    }

    /**
     * Whether a reader shows [card] as faithfully as the reviewer. Not text to speech, whose
     * player is in AnkiDroid's app module, nor a typed answer naming a field the card lacks:
     * the reviewer explains that one.
     */
    @WorkerThread
    fun canShow(
        col: Collection,
        card: Card,
    ): Boolean {
        val output = card.renderOutput(col)
        if ((output.questionAvTags + output.answerAvTags).any { it is TTSTag }) return false
        return TypedTag.find(output.questionText) == null || TypedAnswer.of(col, card, output.questionText) != null
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
