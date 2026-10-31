// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Incremento's entries in the deck list menu.
 *
 * - "Start Incremento session", like desktop's "Start Incremental Learning":
 *   Incremento picks the cards with its saved scheduler settings, fills the
 *   "Incremento Session" deck (which becomes the current deck) and asks for
 *   the reviewer, which [openReviewer] opens.
 * - "Add PDF to Incremento", like desktop's Add PDF.
 */
class IncrementoMenu(
    private val activity: ComponentActivity,
    private val openReviewer: () -> Unit,
) : MenuProvider {
    private val pickPdf =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) activity.lifecycleScope.launch { importDocument(uri, "document.pdf", Ankrimento::importPdf) }
        }

    private val pickEpub =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) activity.lifecycleScope.launch { importDocument(uri, "book.epub", Ankrimento::importEpub) }
        }

    private val pickSyncFolder =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                Ankrimento.setSyncFolder(activity, uri)
                activity.lifecycleScope.launch { syncNow() }
            }
        }

    init {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Ankrimento.warmUp(activity) }.onFailure { Timber.w(it, "Ankrimento warm-up failed") }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect { event ->
                    when (event) {
                        Ankrimento.Event.EnterReview -> openReviewer()
                        is Ankrimento.Event.Message -> toast(event.text)
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onCreateMenu(
        menu: Menu,
        menuInflater: MenuInflater,
    ) {
        if (!Ankrimento.isSupported) return
        menu.add(Menu.NONE, R.id.ankrimento_start_session, Menu.CATEGORY_SECONDARY, R.string.ankrimento_start_session)
        menu.add(Menu.NONE, R.id.ankrimento_session_settings, Menu.CATEGORY_SECONDARY, R.string.ankrimento_session_settings)
        menu.add(Menu.NONE, R.id.ankrimento_add_pdf, Menu.CATEGORY_SECONDARY, R.string.ankrimento_add_pdf)
        menu.add(Menu.NONE, R.id.ankrimento_add_epub, Menu.CATEGORY_SECONDARY, R.string.ankrimento_add_epub)
        menu.add(Menu.NONE, R.id.ankrimento_sync_now, Menu.CATEGORY_SECONDARY, R.string.ankrimento_sync_now)
        menu.add(Menu.NONE, R.id.ankrimento_sync_folder, Menu.CATEGORY_SECONDARY, R.string.ankrimento_sync_folder)
        menu.add(Menu.NONE, R.id.ankrimento_profile, Menu.CATEGORY_SECONDARY, R.string.ankrimento_profile)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.ankrimento_start_session -> activity.lifecycleScope.launch { startSession() }
            R.id.ankrimento_session_settings -> activity.lifecycleScope.launch { editSessionSettings() }
            R.id.ankrimento_add_pdf -> pickPdf.launch(arrayOf("application/pdf"))
            R.id.ankrimento_add_epub -> pickEpub.launch(arrayOf("application/epub+zip"))
            R.id.ankrimento_sync_now -> activity.lifecycleScope.launch { syncNow() }
            R.id.ankrimento_sync_folder -> pickSyncFolder.launch(null)
            R.id.ankrimento_profile -> editProfile()
            else -> return false
        }
        return true
    }

    private suspend fun startSession() {
        Timber.i("Starting Incremento session")
        runCatching {
            CollectionManager.withCol { Ankrimento.start(activity, this) }
            // Bring in other devices' reading before choosing cards.
            if (!sync(reportUpToDate = false)) return
            withContext(Dispatchers.IO) { Ankrimento.startSession() }
        }.onFailure { error ->
            Timber.w(error, "Incremento session failed")
            toast(activity.getString(R.string.ankrimento_session_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun editSessionSettings() {
        val settings =
            CollectionManager.withCol {
                Ankrimento.start(activity, this)
                Ankrimento.sessionSettings()
            }
        SessionSettingsDialog.show(activity, settings) { changed ->
            activity.lifecycleScope.launch { CollectionManager.withCol { Ankrimento.saveSessionSettings(changed) } }
        }
    }

    /** Imports a picked document with [import] (Incremento copies it into its own folder). */
    private suspend fun importDocument(
        uri: Uri,
        fallbackName: String,
        import: (path: String, title: String) -> Long,
    ) {
        Timber.i("Adding a document to Incremento")
        runCatching {
            val name = displayName(uri) ?: fallbackName
            // Stage the picked file where Incremento's importer can read it.
            val staged = File(activity.cacheDir, "ankrimento-import").apply { mkdirs() }.resolve(File(name).name)
            withContext(Dispatchers.IO) {
                activity.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "cannot read $uri" }
                    staged.outputStream().use { input.copyTo(it) }
                }
            }
            try {
                CollectionManager.withCol {
                    Ankrimento.start(activity, this)
                    import(staged.absolutePath, staged.nameWithoutExtension)
                }
            } finally {
                staged.delete()
            }
            staged.nameWithoutExtension
        }.onSuccess { title ->
            toast(activity.getString(R.string.ankrimento_pdf_added, title))
        }.onFailure { error ->
            Timber.w(error, "Adding a document failed")
            toast(activity.getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun syncNow() {
        runCatching {
            CollectionManager.withCol { Ankrimento.start(activity, this) }
            if (!Ankrimento.isSyncConfigured) {
                toast(activity.getString(R.string.ankrimento_sync_no_folder))
                return
            }
            sync(reportUpToDate = true)
        }.onFailure { error ->
            Timber.w(error, "Incremento sync failed")
            toast(activity.getString(R.string.ankrimento_sync_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    /**
     * Syncs with the sync folder, reporting the outcome. Returns false when a
     * conflict needs the user's choice first (a dialog asks for it).
     */
    private suspend fun sync(reportUpToDate: Boolean): Boolean {
        val result = CollectionManager.withCol { Ankrimento.sync() } ?: return true
        val message =
            when (result.state) {
                "pull" -> activity.getString(R.string.ankrimento_sync_pulled, result.remoteDevice)
                "push" -> activity.getString(R.string.ankrimento_sync_pushed)
                "remote_incomplete" -> activity.getString(R.string.ankrimento_sync_incomplete)
                "conflict" -> {
                    showConflict(result.remoteDevice)
                    return false
                }
                else -> if (reportUpToDate) activity.getString(R.string.ankrimento_sync_up_to_date) else null
            }
        val lease = result.leaseHolder.takeIf { it.isNotEmpty() }?.let { activity.getString(R.string.ankrimento_sync_open_elsewhere, it) }
        listOfNotNull(message, lease).joinToString("\n").takeIf { it.isNotEmpty() }?.let(::toast)
        return true
    }

    private fun showConflict(remoteDevice: String) {
        fun resolve(action: String) =
            activity.lifecycleScope.launch {
                runCatching { CollectionManager.withCol { Ankrimento.sync(action) } }
                    .onSuccess { toast(activity.getString(R.string.ankrimento_sync_resolved)) }
                    .onFailure { toast(activity.getString(R.string.ankrimento_sync_failed, it.message ?: it.javaClass.simpleName)) }
            }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankrimento_sync_conflict_title)
            .setMessage(activity.getString(R.string.ankrimento_sync_conflict_message, remoteDevice))
            .setPositiveButton(R.string.ankrimento_sync_keep_device) { _, _ -> resolve("keep_device") }
            .setNegativeButton(R.string.ankrimento_sync_keep_folder) { _, _ -> resolve("keep_folder") }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun editProfile() {
        val settings = AnkrimentoSettings(activity)
        val input = EditText(activity).apply { setText(settings.profile) }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankrimento_profile)
            .setMessage(R.string.ankrimento_profile_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> settings.profile = input.text.toString() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
