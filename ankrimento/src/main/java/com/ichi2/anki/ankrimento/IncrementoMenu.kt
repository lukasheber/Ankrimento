// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.Activity
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.collectAsState
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
import com.ichi2.anki.ankrimento.ui.shouldShowOnboarding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** The deck list opened to sync just after logging in to AnkiWeb: the first-run guide waits for the next start. */
    deferGuide: Boolean = false,
    private val openReviewer: () -> Unit,
) {
    private val settings = AnkrimentoSettings(activity)

    /** A session was asked for and has not reached review: its message says why not. */
    private var sessionStarting = false
    private var sessionDeckId by mutableStateOf<Long?>(null)
    private var sessionRemaining by mutableStateOf<Int?>(null)
    private var plan by mutableStateOf<TodayPlan?>(null)
    private var dailyPlan by mutableStateOf(settings.dailyPlan)

    /** The preview's Start: the session starts here, where its review opens. */
    private val preview =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> activity.lifecycleScope.launch { startSession() }
                SessionPreviewActivity.RESULT_RESUME -> activity.lifecycleScope.launch { continueOrStartSession() }
            }
        }

    init {
        // Desktop's first-run guide: once, the first time Incremento opens (and again if its version rises).
        // Not over the first sync, which may ask which collection to keep: the guide waits for the next start.
        if (!deferGuide && !guideShown && shouldShowOnboarding(settings.onboardingCompletedVersion)) {
            guideShown = true
            activity.startActivity(GettingStartedActivity.intent(activity))
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Ankrimento.warmUp(activity) }.onFailure { Timber.w(it, "Ankrimento warm-up failed") }
            // Then the rest of the start, so the first Incremento screen opens without waiting for it.
            if (settings.hasStarted) {
                runCatching { CollectionManager.withCol { Ankrimento.start(activity, this) } }
                    .onFailure { Timber.w(it, "Ankrimento start failed") }
                // The day's plan needs the backend: show it as soon as that is running.
                refreshSessionEntry()
                // A first sync that stopped early (the app was closed) goes on by itself.
                Ankrimento.resumeUnfinishedSync()
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
                        // The sync that follows each AnkiWeb sync: said only when it changed something.
                        is Ankrimento.Event.Synced -> {
                            toast(doneText(event.result))
                            onDeckListChanged()
                        }
                        is Ankrimento.Event.SyncReport -> showSyncReport(event)
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
                        val transfer by Ankrimento.documentTransfer.collectAsState()
                        val documentsLeft by Ankrimento.documentsLeft.collectAsState()
                        SessionEntryCard(
                            state = DayState.of(plan, sessionRemaining, dailyPlan),
                            plan = plan.takeIf { dailyPlan },
                            transfer = transfer,
                            documentsLeft = documentsLeft,
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

    private var refreshJob: Job? = null

    /**
     * The deck list changed (a sync, above all one that replaced the collection): the card follows, as when the list
     * comes back on screen. A moment later, so typing in the deck search does not ask for the day's plan at every key.
     */
    fun onDeckListChanged() {
        refreshJob?.cancel()
        refreshJob =
            activity.lifecycleScope.launch {
                delay(REFRESH_DELAY_MS)
                refreshSessionEntry()
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
            SessionCardAction(IncrementoIcons.School, R.string.ankrimento_getting_started) {
                activity.startActivity(GettingStartedActivity.intent(activity))
            },
            SessionCardAction(IncrementoIcons.Contents, R.string.ankrimento_session_preview) {
                val (mode, count) = nextSessionMode(plan, dailyPlan, settings.newPerDay)
                preview.launch(SessionPreviewActivity.intent(activity, sessionRemaining ?: 0, mode, count))
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
            // Without a hub this opens where one is set up; a session start syncs too.
            SessionCardAction(IncrementoIcons.Sync, R.string.ankrimento_sync_now) { activity.lifecycleScope.launch { syncNow() } },
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
        dailyPlan = settings.dailyPlan
        runCatching {
            CollectionManager.withCol { SessionDeck.remaining(this, settings.sessionDeckName) }
        }.onSuccess { (deckId, remaining) ->
            sessionDeckId = deckId
            sessionRemaining = remaining
        }.onFailure { Timber.w(it, "Reading the Incremento session deck failed") }
        // Needs the Python side; until that runs (or with the plan off) the card is upstream's own.
        plan =
            if (dailyPlan) {
                runCatching { withContext(Dispatchers.IO) { Ankrimento.todayPlan(settings.newPerDay) } }
                    .onFailure { Timber.d(it, "The day's plan is not available yet") }
                    .getOrNull()
            } else {
                null
            }
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
            // reached, must not stop the session: say so and go on with what this device has. One slice only: the
            // records come at once, and documents still to come (a big first sync) follow in the background.
            runCatching { sync(reportUpToDate = false, maxSlices = 1) }.onFailure { error ->
                Timber.w(error, "Incremento sync before the session failed")
                toast(activity.getString(R.string.ankrimento_sync_failed, error.message ?: error.javaClass.simpleName))
            }
            sessionStarting = true
            withContext(Dispatchers.IO) {
                // Chosen now, from what is due now (the card may have been showing yesterday's plan).
                val today = if (settings.dailyPlan) runCatching { Ankrimento.todayPlan(settings.newPerDay) }.getOrNull() else null
                val (mode, count) = nextSessionMode(today, settings.dailyPlan, settings.newPerDay)
                Ankrimento.startSession(mode, count)
            }
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
                // Nothing to sync with yet: go to where it is set up.
                activity.startActivity(IncrementoSettingsActivity.intent(activity, devicePage = true))
                return
            }
            sync(reportUpToDate = true)
        }.onFailure { error ->
            Timber.w(error, "Incremento sync failed")
            toast(activity.getString(R.string.ankrimento_sync_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    /**
     * Syncs with the sync hub, reporting what changed. Records changed on two devices keep this device's value. While
     * documents move, the session card shows how far it is. With [maxSlices] it stops early and the rest goes on in
     * the background.
     */
    private suspend fun sync(
        reportUpToDate: Boolean,
        maxSlices: Int? = null,
    ) {
        // A quick sync does not wait behind a long one already bringing everything in.
        if (maxSlices != null && Ankrimento.isSyncing) return
        val result = (if (maxSlices == null) Ankrimento.syncInSlices() else Ankrimento.syncInSlices(maxSlices)) ?: return
        if (result.more) Ankrimento.resumeUnfinishedSync()
        val changed = result.sent > 0 || result.received > 0 || result.applied > 0 || result.filesReceived > 0 || result.filesSent > 0
        listOfNotNull(
            when {
                changed -> doneText(result)
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

    /** What a sync moved, in words: documents first (what a big sync is mostly about), then records. */
    private fun doneText(result: Ankrimento.SyncResult): String {
        val resources = activity.resources
        return listOfNotNull(
            result.filesReceived.takeIf { it > 0 }?.let {
                resources.getQuantityString(R.plurals.ankrimento_sync_documents_received, it, it)
            },
            result.filesSent.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.ankrimento_sync_documents_sent, it, it) },
            activity
                .getString(R.string.ankrimento_sync_done, result.sent, result.received)
                .takeIf { result.sent > 0 || result.received > 0 || (result.filesReceived == 0 && result.filesSent == 0) },
            result.filesLeft.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.ankrimento_sync_documents_left, it, it) },
        ).joinToString("\n")
    }

    /**
     * After a Sync the person asked for: the cards came through AnkiWeb, and this says whether the computer's
     * Incremento data came too, and when the computer last changed something, so someone who studied there knows the
     * phone has it before going on. Waits for OK; an unreachable computer offers to try again.
     */
    private fun showSyncReport(report: Ankrimento.Event.SyncReport) {
        val result = report.result
        val dialog = AlertDialog.Builder(activity)
        if (result == null) {
            val detail =
                report.error
                    .orEmpty()
                    .replace(PYTHON_ERROR_NAME, "")
                    .trim()
            dialog
                .setTitle(R.string.ankrimento_sync_report_unreached_title)
                .setMessage(activity.getString(R.string.ankrimento_sync_report_unreached, detail).trim())
                .setNeutralButton(R.string.ankrimento_sync_report_retry) { _, _ -> activity.lifecycleScope.launch { retrySyncReport() } }
                .setPositiveButton(android.R.string.ok, null)
        } else {
            val resources = activity.resources
            val lines =
                buildList {
                    add(activity.getString(R.string.ankrimento_sync_report_cards))
                    when {
                        result.changesIn > 0 ->
                            add(resources.getQuantityString(R.plurals.ankrimento_sync_report_received, result.changesIn, result.changesIn))
                        result.filesReceived == 0 -> add(activity.getString(R.string.ankrimento_sync_report_nothing_new))
                    }
                    if (result.filesReceived > 0) {
                        add(
                            resources.getQuantityString(
                                R.plurals.ankrimento_sync_documents_received,
                                result.filesReceived,
                                result.filesReceived,
                            ),
                        )
                    }
                    if (result.sent > 0) add(resources.getQuantityString(R.plurals.ankrimento_sync_report_sent, result.sent, result.sent))
                    if (result.filesLeft > 0) {
                        add(
                            resources.getQuantityString(
                                R.plurals.ankrimento_sync_report_documents_left,
                                result.filesLeft,
                                result.filesLeft,
                            ),
                        )
                    }
                    result.peerChangedAt?.let { add(activity.getString(R.string.ankrimento_sync_report_last_change, whenText(it))) }
                    if (result.conflicts > 0) add(activity.getString(R.string.ankrimento_sync_conflicts, result.conflicts))
                }
            dialog
                .setTitle(if (result.filesLeft > 0) R.string.ankrimento_sync_report_still_title else R.string.ankrimento_sync_report_title)
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton(android.R.string.ok, null)
        }
        dialog.show()
        onDeckListChanged()
    }

    /** Try again: only Incremento's sync, the cards having come through already. */
    private suspend fun retrySyncReport() {
        val report =
            runCatching { Ankrimento.syncInSlices() }.fold(
                onSuccess = { Ankrimento.Event.SyncReport(it) },
                onFailure = {
                    Timber.w(it, "Incremento sync, tried again, failed")
                    Ankrimento.Event.SyncReport(null, it.message ?: it.javaClass.simpleName)
                },
            )
        showSyncReport(report)
    }

    /** "22:40" today, with the date on another day. */
    private fun whenText(epochSeconds: Double): String {
        val millis = (epochSeconds * 1000).toLong()
        val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_DATE
        return DateUtils.formatDateTime(activity, millis, flags)
    }

    private companion object {
        /** The first-run guide was opened in this run of the app. */
        var guideShown = false

        private const val REFRESH_DELAY_MS = 300L

        /** A Python exception's name in front of its message, as Chaquopy reports it ("StoreError: "). */
        private val PYTHON_ERROR_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.]*: ")
    }
}
