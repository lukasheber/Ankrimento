// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.edit
import androidx.core.net.toUri
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic full backups, as desktop's (`backend/backup_schedule.py`): opt-in,
 * into a folder the user picks, when the app opens and every few hours, keeping
 * the latest few by upstream's own rotation.
 *
 * Desktop keeps its policy per profile in Incremento's settings, with a desktop
 * folder path; the phone's folder is an Android storage folder, so its policy
 * is kept on this device instead. Desktop's "when the profile closes" has no
 * dependable moment on a phone.
 */
object AutomaticBackups {
    enum class Trigger { OPEN, INTERVAL }

    data class Policy(
        val enabled: Boolean = false,
        val folder: Uri? = null,
        val onOpen: Boolean = true,
        val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
        val versions: Int = DEFAULT_VERSIONS,
        val lastSuccess: Long = 0L,
    ) {
        /** Mirrors `backup_schedule.is_due`. */
        fun isDue(
            trigger: Trigger,
            now: Long,
        ): Boolean {
            if (!enabled || folder == null) return false
            return when (trigger) {
                Trigger.OPEN -> onOpen
                Trigger.INTERVAL -> intervalHours > 0 && now - lastSuccess >= intervalHours * HOUR_MS
            }
        }
    }

    fun load(context: Context): Policy {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Policy(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            folder = prefs.getString(KEY_FOLDER, null)?.toUri(),
            onOpen = prefs.getBoolean(KEY_ON_OPEN, true),
            intervalHours = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_HOURS),
            versions = prefs.getInt(KEY_VERSIONS, DEFAULT_VERSIONS).coerceIn(1, MAX_VERSIONS),
            lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0L),
        )
    }

    fun save(
        context: Context,
        policy: Policy,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ENABLED, policy.enabled)
            putString(KEY_FOLDER, policy.folder?.toString())
            putBoolean(KEY_ON_OPEN, policy.onOpen)
            putInt(KEY_INTERVAL, policy.intervalHours)
            putInt(KEY_VERSIONS, policy.versions.coerceIn(1, MAX_VERSIONS))
            putLong(KEY_LAST_SUCCESS, policy.lastSuccess)
        }
    }

    /** Keeps access to [folder] across restarts, then makes it the backup folder. */
    fun chooseFolder(
        context: Context,
        folder: Uri,
    ): Policy {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(folder, flags)
        return load(context).copy(enabled = true, folder = folder).also { save(context, it) }
    }

    private val running = AtomicBoolean(false)
    private var openedThisProcess = false

    /** Backs up quietly when [trigger] makes it due, as desktop does; whether one was made. */
    suspend fun runIfDue(
        context: Context,
        trigger: Trigger,
    ): Boolean {
        if (trigger == Trigger.OPEN) {
            if (openedThisProcess) return false
            openedThisProcess = true
        }
        if (!load(context).isDue(trigger, System.currentTimeMillis())) return false
        return runCatching { backUp(context) }
            .onFailure { Timber.w(it, "Automatic backup failed") }
            .getOrNull() != null
    }

    /** Writes a full backup into the folder's next slot and removes what rotation drops; the file's name. */
    suspend fun backUp(context: Context): String? {
        val policy = load(context)
        val folder = policy.folder ?: return null
        if (!running.compareAndSet(false, true)) return null
        try {
            val temp = File(context.cacheDir, "ankrimento-automatic-backup.zip")
            withCol {
                Ankrimento.start(context, this)
                Ankrimento.exportFullBackup(temp)
            }
            val written =
                withContext(Dispatchers.IO) {
                    val files = FolderFiles(context, folder)
                    val (write, remove) = Ankrimento.automaticBackupPlan(files.list(), policy.versions)
                    files.write(write, temp)
                    remove.forEach(files::delete)
                    temp.delete()
                    write
                }
            save(context, load(context).copy(lastSuccess = System.currentTimeMillis()))
            return written
        } finally {
            running.set(false)
        }
    }

    /** The files directly in a picked folder: names, modification times, writing and deleting by name. */
    private class FolderFiles(
        context: Context,
        private val tree: Uri,
    ) {
        private val resolver = context.contentResolver
        private val rootId = DocumentsContract.getTreeDocumentId(tree)

        private data class Entry(
            val id: String,
            val name: String,
            val modified: Long,
        )

        private fun entries(): List<Entry> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            val projection =
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
            return resolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(2) == Document.MIME_TYPE_DIR) continue
                        add(Entry(cursor.getString(0), cursor.getString(1), if (cursor.isNull(3)) 0L else cursor.getLong(3)))
                    }
                }
            } ?: emptyList()
        }

        fun list(): List<Pair<String, Long>> = entries().map { it.name to it.modified }

        fun write(
            name: String,
            source: File,
        ) {
            val existing = entries().firstOrNull { it.name == name }
            val uri =
                existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
                    ?: DocumentsContract.createDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                        "application/zip",
                        name,
                    )
                    ?: throw FileNotFoundException("cannot create $name")
            val output = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(name)
            output.use { out -> source.inputStream().use { it.copyTo(out) } }
        }

        fun delete(name: String) {
            entries().firstOrNull { it.name == name }?.let {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.id))
            }
        }
    }

    const val MAX_VERSIONS = 20
    private const val DEFAULT_VERSIONS = 5
    private const val DEFAULT_INTERVAL_HOURS = 24
    private const val HOUR_MS = 3_600_000L
    private const val PREFERENCES = "ankrimento_automatic_backups"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER = "folder"
    private const val KEY_ON_OPEN = "on_open"
    private const val KEY_INTERVAL = "interval_hours"
    private const val KEY_VERSIONS = "versions"
    private const val KEY_LAST_SUCCESS = "last_success"
}
