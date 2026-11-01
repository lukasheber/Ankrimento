// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * How this device lays out text in the EPUB reader: typeface, line spacing
 * and margins suit the screen in hand, so they are kept per device. Text size
 * and page colour are the book's own settings, shared with desktop.
 */
data class ReadingPreferences(
    val typeface: Typeface = Typeface.ORIGINAL,
    val spacing: Spacing = Spacing.NORMAL,
    val margins: Margins = Margins.NORMAL,
) {
    enum class Typeface(
        val css: String?,
    ) {
        /** The book's own fonts. */
        ORIGINAL(null),
        SERIF("Georgia, 'Noto Serif', serif"),
        SANS("Roboto, 'Noto Sans', sans-serif"),
    }

    enum class Spacing(
        val lineHeight: Double,
    ) {
        COMPACT(1.35),
        NORMAL(1.6),
        RELAXED(1.9),
    }

    enum class Margins(
        val em: Double,
    ) {
        NARROW(0.75),
        NORMAL(1.5),
        WIDE(3.0),
    }

    /** Applies these preferences to the chapter on screen; upstream's highlights and read mark follow the resize. */
    fun script(): String {
        val options =
            JSONObject()
                .put("font", typeface.css ?: JSONObject.NULL)
                .put("lineHeight", spacing.lineHeight)
                .put("margin", margins.em)
        return "(function (o) {" +
            " var style = document.getElementById('ankrimento-reading');" +
            " if (!style) { style = document.createElement('style'); style.id = 'ankrimento-reading';" +
            " document.head.appendChild(style); }" +
            // Upstream's page style pads `html.incremento-epub-scaled body` with !important: the same
            // selector, added after it, is needed for these margins to apply.
            " var css = 'body, html.incremento-epub-scaled body { box-sizing: border-box !important;" +
            " max-width: 40em !important; margin: 0 auto !important;" +
            " padding: 1em ' + o.margin + 'em 3em !important; line-height: ' + o.lineHeight + ' !important; }'" +
            // Books wrap their text in blocks with their own line-height: those inherit the body's too.
            " + ' body div, body section, body article, body p, body li, body blockquote, body dd, body span" +
            " { line-height: inherit !important; }'" +
            " + ' body img, body svg, body video { max-width: 100% !important; height: auto !important; }';" +
            " if (o.font) css += ' body, body p, body li, body span, body div, body blockquote'" +
            " + ' { font-family: ' + o.font + ' !important; }';" +
            " style.textContent = css; window.dispatchEvent(new Event('resize'));" +
            " })($options);"
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_TYPEFACE, typeface.name)
            putString(KEY_SPACING, spacing.name)
            putString(KEY_MARGINS, margins.name)
        }
    }

    companion object {
        private const val PREFERENCES = "ankrimento_reading"
        private const val KEY_TYPEFACE = "typeface"
        private const val KEY_SPACING = "spacing"
        private const val KEY_MARGINS = "margins"

        fun load(context: Context): ReadingPreferences {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

            fun <T : Enum<T>> read(
                key: String,
                values: Array<T>,
                default: T,
            ): T = values.firstOrNull { it.name == preferences.getString(key, null) } ?: default
            return ReadingPreferences(
                typeface = read(KEY_TYPEFACE, Typeface.entries.toTypedArray(), Typeface.ORIGINAL),
                spacing = read(KEY_SPACING, Spacing.entries.toTypedArray(), Spacing.NORMAL),
                margins = read(KEY_MARGINS, Margins.entries.toTypedArray(), Margins.NORMAL),
            )
        }
    }
}
