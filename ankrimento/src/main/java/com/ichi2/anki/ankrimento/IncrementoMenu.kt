// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import com.ichi2.anki.ankrimento.ui.SessionCardAction
import com.ichi2.anki.ankrimento.ui.SessionEntryCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Incremento on the deck list: the fixed way in at the top
 * ([attachSessionEntry]), the session deck as a card that continues the
 * session, or starts one when it is empty. Tapping the session deck in the
 * list does the same ([onDeckClick]).
 *
 * Starting a session is desktop's "Start Incremental Learning": Incremento
 * picks the cards with its saved scheduler settings, fills the "Incremento
 * Session" deck (which becomes the current deck) and asks for the reviewer,
 * which [openReviewer] opens.
 *
 * The card's menu leads to Incremento's other places: the library (and its
 * Add), the knowledge tree, statistics, and syncing when a folder is set.
 */
class IncrementoMenu(
    private val activity: ComponentActivity,
    private val openReviewer: () -> Unit,
) {
    private val settings = AnkrimentoSettings(activity)

    /** A session was asked for and has not reached review: its message says why not. */
    private var sessionStarting = false
    private var sessionDeckId by mutableStateOf<Long?>(null)
    private var sessionRemaining by mutableStateOf<Int?>(null)

    /** The preview's Start: the session starts here, where its review opens. */
    private val preview =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) activity.lifecycleScope.launch { startSession() }
        }

    init {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Ankrimento.warmUp(activity) }.onFailure { Timber.w(it, "Ankrimento warm-up failed") }
            // Then the rest of the start, so the first Incremento screen opens without waiting for it.
            if (settings.hasStarted) {
                runCatching { CollectionManager.withCol { Ankrimento.start(activity, this) } }
                    .onFailure { Timber.w(it, "Ankrimento start failed") }
            }
        }
        // Desktop's automatic backups: when the app opens, then whenever the interval has passed.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch(Dispatchers.IO) {
                    AutomaticBackups.runIfDue(activity, AutomaticBackups.Trigger.OPEN) ||
                        AutomaticBackups.runIfDue(activity, AutomaticBackups.Trigger.INTERVAL)
                }
            }
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
                            actions = cardActions(),
                        )
                    }
                }
            }
        container.addView(card, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { refreshSessionEntry() }
        }
    }

    private fun cardActions(): List<SessionCardAction> =
        listOfNotNull(
            SessionCardAction(IncrementoIcons.Book, R.string.ankrimento_library) {
                activity.startActivity(IncrementoHubActivity.intent(activity))
            },
            SessionCardAction(IncrementoIcons.Add, R.string.ankrimento_add_to_incremento) {
                activity.startActivity(IncrementoHubActivity.intent(activity, add = true))
            },
            SessionCardAction(IncrementoIcons.Contents, R.string.ankrimento_session_preview) {
                preview.launch(SessionPreviewActivity.intent(activity))
            },
            SessionCardAction(IncrementoIcons.Search, R.string.ankrimento_search_all) {
                activity.startActivity(SearchAllActivity.intent(activity))
            },
            SessionCardAction(IncrementoIcons.Tree, R.string.ankrimento_knowledge_tree_title) {
                activity.startActivity(KnowledgeTreeActivity.intent(activity))
            },
            SessionCardAction(IncrementoIcons.Chart, R.string.ankrimento_statistics_menu) {
                activity.startActivity(StatisticsActivity.intent(activity))
            },
            // The folder or hub is chosen in settings; a session start syncs too.
            (settings.syncFolder ?: settings.syncHubCode.takeIf { it.isNotEmpty() })?.let {
                SessionCardAction(IncrementoIcons.Sync, R.string.ankrimento_sync_now) { activity.lifecycleScope.launch { syncNow() } }
            },
        )

    /**
     * Whether [fullDeckName] is a session deck, which the deck list shows as the session card
     * instead: any preset's, since each preset keeps its own session and the card follows the
     * selected one.
     */
    fun isSessionDeck(fullDeckName: String): Boolean = SessionDeck.isSessionDeckName(fullDeckName)

    /** Whether a filtered deck stays out of the deck list: the session's, or an idle document review's. */
    fun isHiddenFilteredDeck(
        fullDeckName: String,
        dueCards: Int,
    ): Boolean = isSessionDeck(fullDeckName) || SessionDeck.isHiddenReviewDeck(fullDeckName, dueCards)

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
            // Bring in other devices' reading before choosing cards. A hub that is off, or a folder that cannot be
            // reached, must not stop the session: say so and go on with what this device has.
            runCatching { sync(reportUpToDate = false) }.onFailure { error ->
                Timber.w(error, "Incremento sync before the session failed")
                toast(activity.getString(R.string.ankrimento_sync_failed, error.message ?: error.javaClass.simpleName))
            }
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

    /** Syncs with the sync folder, reporting what changed. Records changed on two devices keep this device's value. */
    private suspend fun sync(reportUpToDate: Boolean) {
        val result = CollectionManager.withCol { Ankrimento.sync() } ?: return
        val changed = result.sent > 0 || result.received > 0 || result.applied > 0
        listOfNotNull(
            when {
                changed -> activity.getString(R.string.ankrimento_sync_done, result.sent, result.received)
                reportUpToDate -> activity.getString(R.string.ankrimento_sync_up_to_date)
                else -> null
            },
            result.conflicts.takeIf { it > 0 }?.let { activity.getString(R.string.ankrimento_sync_conflicts, it) },
            result.problems.size
                .takeIf { it > 0 }
                ?.let { activity.getString(R.string.ankrimento_sync_problems, it) },
        ).takeIf { it.isNotEmpty() }?.joinToString("\n")?.let(::toast)
    }

    private fun toast(text: String) {
        activity.showToast(text)
    }
}
