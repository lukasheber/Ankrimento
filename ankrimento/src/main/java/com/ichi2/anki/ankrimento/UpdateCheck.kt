// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import androidx.annotation.WorkerThread
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer Ankrimento is out on GitHub: asked at most once a day, for the project's public list of releases
 * (betas included, as every release so far is one). Nothing about the user is sent beyond what any web request
 * carries. A version the user skips is not offered again; a later one is. Turned off in *This device*.
 */
object UpdateCheck {
    private const val RELEASES_URL = "https://api.github.com/repos/lukasheber/Ankrimento/releases?per_page=10"
    private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 512 * 1024
    private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-?beta(\d+))?(?:-debug)?$""")

    /** A release to offer: its tag (`v0.1.0-beta8`), its title and its page. */
    data class Release(
        val tag: String,
        val name: String,
        val url: String,
    )

    /** major, minor, patch and beta number; a release comes after all its betas. Null when [version] is not ours. */
    private fun parse(version: String): List<Int>? {
        val found = VERSION.find(version.trim()) ?: return null
        val (major, minor, patch, beta) = found.destructured
        return listOf(major.toInt(), minor.toInt(), patch.toInt(), beta.toIntOrNull() ?: Int.MAX_VALUE)
    }

    fun isNewer(
        tag: String,
        current: String,
    ): Boolean {
        val latest = parse(tag) ?: return false
        val mine = parse(current) ?: return false
        return latest.zip(mine).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
    }

    /** The newest published release in GitHub's answer (newest first), or null. */
    private fun newest(body: String): Release? {
        val releases = JSONArray(body)
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            val tag = release.optString("tag_name")
            val url = release.optString("html_url")
            if (release.optBoolean("draft") || parse(tag) == null || !url.startsWith("https://")) continue
            return Release(tag, release.optString("name").ifBlank { tag }, url)
        }
        return null
    }

    /**
     * The newer release to offer, or null: switched off, asked less than a day ago, offline, nothing newer, or
     * skipped before. [fetch] reads GitHub's answer; [current] is this app's version.
     */
    @WorkerThread
    fun check(
        context: Context,
        now: Long = TimeManager.time.intTimeMS(),
        current: String? = installedVersion(context),
        fetch: () -> String = ::download,
    ): Release? {
        val settings = AnkrimentoSettings(context)
        if (!settings.checkForUpdates || current == null) return null
        if (now - settings.updateCheckedAt in 0 until CHECK_EVERY_MS) return null
        val body =
            try {
                fetch()
            } catch (_: IOException) {
                return null // offline: try again next time
            }
        settings.updateCheckedAt = now
        val release =
            try {
                newest(body)
            } catch (_: JSONException) {
                null
            } ?: return null
        return release.takeIf { isNewer(it.tag, current) && it.tag != settings.updateDeclined }
    }

    /** The user chose not to install [tag]: it is not offered again. */
    fun skip(
        context: Context,
        tag: String,
    ) {
        AnkrimentoSettings(context).updateDeclined = tag
    }

    private fun installedVersion(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    private fun download(): String {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Ankrimento")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered ${connection.responseCode}")
            // At most MAX_BYTES, read in a way every supported Android has (readNBytes needs API 33).
            return connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() < MAX_BYTES) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
