// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.content.Context
import org.json.JSONObject

/** Incremento's statistics as text: today, lifetime, reading/watching and the last days. */
internal object StatisticsDialog {
    fun show(
        context: Context,
        stats: JSONObject,
    ) {
        val text =
            buildString {
                appendLine(context.getString(R.string.ankrimento_stats_today))
                appendBlock(stats.getJSONObject("today"))
                appendLine()
                appendLine(context.getString(R.string.ankrimento_stats_lifetime))
                appendBlock(stats.getJSONObject("lifetime"))
                appendLine()
                val media = stats.getJSONObject("media")
                appendLine(
                    context.getString(
                        R.string.ankrimento_stats_media,
                        media.optInt("pages"),
                        media.optInt("videos_watched"),
                        duration(media.optDouble("video_watch_seconds", 0.0)),
                    ),
                )
                appendLine()
                val history = stats.getJSONArray("history")
                for (index in 0 until history.length()) {
                    val day = history.getJSONObject(index)
                    appendLine(
                        "${day.getString(
                            "date",
                        )}  ${day.getInt("cards")} · ${duration(day.getDouble("seconds"))} · ${day.getInt("pages")} p",
                    )
                }
            }
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankrimento_statistics_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun StringBuilder.appendBlock(block: JSONObject) {
        val cards = block.getJSONObject("cards")
        val seconds = block.getJSONObject("seconds")
        val kinds = (cards.keys().asSequence() + seconds.keys().asSequence()).toSortedSet()
        if (kinds.isEmpty()) appendLine("  –")
        for (kind in kinds) {
            appendLine("  $kind: ${cards.optInt(kind)} · ${duration(seconds.optDouble(kind, 0.0))}")
        }
    }

    private fun duration(seconds: Double): String {
        val total = seconds.toLong()
        return if (total >= 3600) "%dh %02dm".format(total / 3600, total % 3600 / 60) else "%dm %02ds".format(total / 60, total % 60)
    }
}
