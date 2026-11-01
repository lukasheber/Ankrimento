// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Desktop's links from an extract back to its source (`pycmd` commands its
 * readers write into cards, routed by `_on_pycmd` in upstream's `__init__.py`),
 * opened in the phone's readers: a web page at its URL, a PDF page (with the
 * excerpt found), an EPUB chapter, a video at its moment.
 */
object SourceLinks {
    fun intentFor(
        context: Context,
        message: String,
    ): Intent? {
        val parts = message.split(':')
        return when {
            message.startsWith("incremento_open_web:") -> {
                val (card, url) = message.removePrefix("incremento_open_web:").split(':', limit = 2).takeIf { it.size == 2 } ?: return null
                val cardId = card.toLongOrNull() ?: return null
                WebReaderActivity.intentAt(context, cardId, Uri.decode(url))
            }
            message.startsWith("incremento_open_pdf_ref:") -> {
                val data = runCatching { JSONObject(message.removePrefix("incremento_open_pdf_ref:")) }.getOrNull() ?: return null
                val cardId = data.optLong("card_id").takeIf { it > 0 } ?: return null
                // Desktop finds the excerpt when the link has no scroll position.
                val excerpt = if (data.isNull("scroll_ratio") || !data.has("scroll_ratio")) data.optString("excerpt") else ""
                ReaderActivity.intentAt(context, "pdf", cardId, data.optInt("page", 1).coerceAtLeast(1), excerpt)
            }
            message.startsWith("incremento_open_pdf:") && parts.size == 3 -> {
                val cardId = parts[1].toLongOrNull() ?: return null
                ReaderActivity.intentAt(context, "pdf", cardId, parts[2].toIntOrNull()?.coerceAtLeast(1) ?: return null, "")
            }
            // Mirrors parse_epub_anchor_command: card, chapter, focus offset[, scroll ratio].
            message.startsWith("incremento_open_epub:") && parts.size in 4..5 -> {
                val cardId = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
                val section = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                ReaderActivity.intentAt(context, "epub", cardId, section, "")
            }
            message.startsWith("incremento_open_video:") && parts.size == 3 -> {
                val cardId = parts[1].toLongOrNull() ?: return null
                VideoReaderActivity.intentAt(context, cardId, parts[2].toDoubleOrNull() ?: return null)
            }
            else -> null
        }
    }
}
