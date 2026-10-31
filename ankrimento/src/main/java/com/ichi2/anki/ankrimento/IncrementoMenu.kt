// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.net.Uri
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import com.ichi2.anki.ankrimento.ui.SessionEntryCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Incremento's entries in the deck list menu.
 *
 * - "Start Incremento session", like desktop's "Start Incremental Learning":
 *   Incremento picks the cards with its saved scheduler settings, fills the
 *   "Incremento Session" deck (which becomes the current deck) and asks for
 *   the reviewer, which [openReviewer] opens.
 * - "Add PDF to Incremento", like desktop's Add PDF.
 *
 * And the fixed way in, at the top of the deck list ([attachSessionEntry]):
 * the session deck as a card that continues the session, or starts one when
 * it is empty. Tapping the session deck in the list does the same
 * ([onDeckClick]).
 */
class IncrementoMenu(
    private val activity: ComponentActivity,
    private val openReviewer: () -> Unit,
) : MenuProvider {
    private val settings = AnkrimentoSettings(activity)

    /** A session was asked for and has not reached review: its message says why not. */
    private var sessionStarting = false
    private var sessionDeckId by mutableStateOf<Long?>(null)
    private var sessionRemaining by mutableStateOf<Int?>(null)

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
                        Ankrimento.Event.EnterReview -> {
                            sessionStarting = false
                            openReviewer()
                        }
                        is Ankrimento.Event.Message -> if (sessionStarting) explainEmptySession(event.text) else toast(event.text)
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
        menu.add(Menu.NONE, R.id.ankrimento_knowledge_tree, Menu.CATEGORY_SECONDARY, R.string.ankrimento_knowledge_tree)
        menu.add(Menu.NONE, R.id.ankrimento_statistics, Menu.CATEGORY_SECONDARY, R.string.ankrimento_statistics)
        menu.add(Menu.NONE, R.id.ankrimento_add_pdf, Menu.CATEGORY_SECONDARY, R.string.ankrimento_add_pdf)
        menu.add(Menu.NONE, R.id.ankrimento_add_epub, Menu.CATEGORY_SECONDARY, R.string.ankrimento_add_epub)
        menu.add(Menu.NONE, R.id.ankrimento_new_writing, Menu.CATEGORY_SECONDARY, R.string.ankrimento_new_writing)
        menu.add(Menu.NONE, R.id.ankrimento_sync_now, Menu.CATEGORY_SECONDARY, R.string.ankrimento_sync_now)
        menu.add(Menu.NONE, R.id.ankrimento_sync_folder, Menu.CATEGORY_SECONDARY, R.string.ankrimento_sync_folder)
        menu.add(Menu.NONE, R.id.ankrimento_profile, Menu.CATEGORY_SECONDARY, R.string.ankrimento_profile)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.ankrimento_start_session -> activity.lifecycleScope.launch { startSession() }
            R.id.ankrimento_session_settings -> editSessionSettings()
            R.id.ankrimento_knowledge_tree -> activity.startActivity(KnowledgeTreeActivity.intent(activity))
            R.id.ankrimento_statistics -> activity.lifecycleScope.launch { showStatistics() }
            R.id.ankrimento_add_pdf -> pickPdf.launch(arrayOf("application/pdf"))
            R.id.ankrimento_add_epub -> pickEpub.launch(arrayOf("application/epub+zip"))
            R.id.ankrimento_new_writing -> newWriting()
            R.id.ankrimento_sync_now -> activity.lifecycleScope.launch { syncNow() }
            R.id.ankrimento_sync_folder -> pickSyncFolder.launch(null)
            R.id.ankrimento_profile -> editProfile()
            else -> return false
        }
        return true
    }

    /** Puts the session card at the top of [container], kept current whenever the deck list shows. */
    fun attachSessionEntry(container: ViewGroup) {
        val card =
            ComposeView(activity).apply {
                setContent {
                    IncrementoTheme {
                        SessionEntryCard(
                            remaining = sessionRemaining,
                            onStudy = { activity.lifecycleScope.launch { continueOrStartSession() } },
                            onSettings = { activity.startActivity(IncrementoSettingsActivity.intent(activity)) },
                            onLibrary = { activity.startActivity(IncrementoHubActivity.intent(activity)) },
                        )
                    }
                }
            }
        container.addView(card, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { refreshSessionEntry() }
        }
    }

    /** Whether [fullDeckName] is the session deck, which the deck list shows as the session card instead. */
    fun isSessionDeck(fullDeckName: String): Boolean = fullDeckName == settings.sessionDeckName

    /** The session deck tapped in the list: continue or start, as the session card does. */
    fun onDeckClick(deckId: Long): Boolean {
        if (deckId != sessionDeckId) return false
        activity.lifecycleScope.launch { continueOrStartSession() }
        return true
    }

    private suspend fun refreshSessionEntry() {
        runCatching {
            CollectionManager.withCol { SessionDeck.remaining(this, settings.sessionDeckName) }
        }.onSuccess { (deckId, remaining) ->
            sessionDeckId = deckId
            sessionRemaining = remaining
        }.onFailure { Timber.w(it, "Reading the Incremento session deck failed") }
    }

    /** Continues the session while its deck has cards; otherwise starts a new one. */
    private suspend fun continueOrStartSession() {
        runCatching {
            val continuing =
                CollectionManager.withCol {
                    Ankrimento.start(activity, this)
                    settings.sessionDeckName = Ankrimento.sessionDeckName()
                    val (deckId, remaining) = SessionDeck.remaining(this, settings.sessionDeckName)
                    if (deckId != null && remaining > 0) {
                        decks.select(deckId)
                        true
                    } else {
                        false
                    }
                }
            if (continuing) openReviewer() else startSession()
        }.onFailure { error ->
            Timber.w(error, "Incremento session failed")
            toast(activity.getString(R.string.ankrimento_session_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun startSession() {
        Timber.i("Starting Incremento session")
        runCatching {
            CollectionManager.withCol { Ankrimento.start(activity, this) }
            // Bring in other devices' reading before choosing cards.
            if (!sync(reportUpToDate = false)) return
            sessionStarting = true
            withContext(Dispatchers.IO) { Ankrimento.startSession() }
        }.onFailure { error ->
            Timber.w(error, "Incremento session failed")
            toast(activity.getString(R.string.ankrimento_session_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    /** Upstream found nothing to study: say so where it can't be missed, with the settings that decide it. */
    private fun explainEmptySession(message: String) {
        sessionStarting = false
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankrimento_session_empty_title)
            .setMessage(message)
            .setPositiveButton(R.string.ankrimento_session_settings) { _, _ -> editSessionSettings() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** Session settings are the first section of the settings screen. */
    private fun editSessionSettings() {
        activity.startActivity(IncrementoSettingsActivity.intent(activity))
    }

    /** Imports a picked document with [import] (Incremento copies it into its own folder). */
    private suspend fun importDocument(
        uri: Uri,
        fallbackName: String,
        import: (path: String, title: String) -> Long,
    ) {
        runCatching { DocumentImport.import(activity, uri, fallbackName, import) }
            .onSuccess { (_, title) -> toast(activity.getString(R.string.ankrimento_pdf_added, title)) }
            .onFailure { error ->
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

    private suspend fun showStatistics() {
        val stats =
            CollectionManager.withCol {
                Ankrimento.start(activity, this)
                Ankrimento.statistics()
            }
        StatisticsDialog.show(activity, stats)
    }

    private fun newWriting() {
        val input = EditText(activity).apply { setHint(R.string.ankrimento_new_writing_title) }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.ankrimento_new_writing)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title =
                    input.text
                        .toString()
                        .trim()
                        .ifEmpty { return@setPositiveButton }
                activity.lifecycleScope.launch {
                    runCatching {
                        CollectionManager.withCol {
                            Ankrimento.start(activity, this)
                            Ankrimento.createWriting(title)
                        }
                    }.onSuccess { cardId -> activity.startActivity(WritingActivity.intent(activity, cardId)) }
                        .onFailure { toast(activity.getString(R.string.ankrimento_pdf_add_failed, it.message ?: it.javaClass.simpleName)) }
                }
            }.setNegativeButton(android.R.string.cancel, null)
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

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
