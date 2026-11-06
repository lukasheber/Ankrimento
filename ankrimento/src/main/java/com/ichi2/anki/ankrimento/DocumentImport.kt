// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Adding a picked document (PDF, EPUB) to Incremento, as desktop's Add PDF / Add EPUB. */
internal object DocumentImport {
    /**
     * Copies [uri] where Incremento's importer can read it and imports it with
     * [import]; returns the new card's id and its title (the file's name).
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        fallbackName: String,
        import: (path: String, title: String) -> Long,
    ): Pair<Long, String> {
        Timber.i("Adding a document to Incremento")
        val name = displayName(context, uri) ?: fallbackName
        val staged = File(context.cacheDir, "ankrimento-import").apply { mkdirs() }.resolve(File(name).name)
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStreamSafely(uri).use { input ->
                checkNotNull(input) { "cannot read $uri" }
                staged.outputStream().use { input.copyTo(it) }
            }
        }
        try {
            val cardId =
                CollectionManager.withCol {
                    Ankrimento.start(context, this)
                    import(staged.absolutePath, staged.nameWithoutExtension)
                }
            return cardId to staged.nameWithoutExtension
        } finally {
            staged.delete()
        }
    }

    private fun displayName(
        context: Context,
        uri: Uri,
    ): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

/** When each document was last opened on this device, for "Continue reading". */
internal object RecentDocuments {
    private const val PREFERENCES = "ankrimento_recent"
    private const val KEEP = 30

    fun record(
        context: Context,
        cardId: Long,
    ) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        // Keep the list short: drop the oldest entries.
        val oldest =
            preferences.all.entries
                .sortedByDescending { it.value as? Long ?: 0L }
                .drop(KEEP - 1)
        preferences.edit {
            putLong(cardId.toString(), TimeManager.time.intTimeMS())
            oldest.forEach { remove(it.key) }
        }
    }

    /** Card ids, most recently opened first. */
    fun cardIds(context: Context): List<Long> =
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .all.entries
            .sortedByDescending { it.value as? Long ?: 0L }
            .mapNotNull { it.key.toLongOrNull() }
}
