// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.annotation.WorkerThread
import androidx.core.os.ConfigurationCompat
import anki.collection.OpChanges
import anki.scheduler.CardAnswer.Rating
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.ankrimento.ui.MarkBookmark
import com.ichi2.anki.ankrimento.ui.MarkHighlight
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * Incremento's backend, running in embedded Python on AnkiDroid's collection.
 *
 * Call from the collection queue (`withCol`): Python uses the same backend and
 * undo queue as AnkiDroid, so its work must be serialized with AnkiDroid's.
 */
object Ankrimento {
    /** Desktop's default profile; selects `user_files/<profile>/` as on desktop. */
    const val DEFAULT_PROFILE = "User 1"

    /** Passed to [sync] for no time limit on file transfer. */
    const val NO_BUDGET = -1.0

    /** How long one slice of a large sync transfers files before other work on the collection gets a turn. */
    private const val SLICE_SECONDS = 4.0

    /** A sync that is still not done after this many slices stops and finishes at the next sync. */
    private const val MAX_SLICES = 2000

    private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    /** What Incremento asks of the UI. Collect while a screen can act on it. */
    sealed interface Event {
        /** A session deck is ready: open the reviewer on the current deck. */
        data object EnterReview : Event

        /** `showInfo`, `showWarning` or `tooltip` from Incremento. */
        data class Message(
            val kind: String,
            val text: String,
        ) : Event

        /** The reader showing [cardId] should close (e.g. the document was finished). */
        data class CloseReader(
            val cardId: Long,
        ) : Event

        /** A link in a document should open in the browser. */
        data class OpenUrl(
            val url: String,
        ) : Event

        /** Text was extracted from [sourceCardId]'s document; see [extractDraft]. */
        data class ExtractDraft(
            val sourceCardId: Long,
        ) : Event

        /** A page link in [sourceCardId]'s viewer opens its other end. */
        data class PdfOpen(
            val sourceCardId: Long,
            val place: PdfPlace,
        ) : Event

        /** [cardId]'s viewer asks to link its [page] to another, or to show the page's links. */
        data class PdfPageLinks(
            val cardId: Long,
            val page: Int,
            val create: Boolean,
            val pageY: Double?,
        ) : Event
    }

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = mutableEvents

    private var facade: PyObject? = null

    val isSupported: Boolean
        get() = Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    val isStarted: Boolean
        get() = facade != null

    private var syncExchange: SafExchange? = null
    private var syncFolderLabel = ""
    private var syncHubCode = ""

    /**
     * Starts Python and imports the slow, collection-independent modules, off
     * the collection queue, so a later [start] is quick. Only once Incremento
     * has been used on this device.
     */
    @WorkerThread
    fun warmUp(context: Context) {
        if (!isSupported || facade != null || !AnkrimentoSettings(context).hasStarted) return
        val startedAt = TimeManager.time.intTimeMS()
        startPython(context)
        Python.getInstance().getModule("ankrimento.android").callAttr("warm_up")
        Timber.i("Ankrimento warmed up in %d ms", TimeManager.time.intTimeMS() - startedAt)
    }

    @Synchronized
    private fun startPython(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    @WorkerThread
    @Synchronized
    fun start(
        context: Context,
        col: Collection,
    ) {
        if (facade != null) return
        check(isSupported) { "Ankrimento needs a 64-bit device" }
        val settings = AnkrimentoSettings(context)
        val profile = settings.profile
        settings.hasStarted = true
        startPython(context)
        val python = Python.getInstance()
        val host = AnkrimentoHost(col.backend, ::onHostEvent)
        // Reinstall the shipped add-on whenever the app itself is updated.
        val buildId =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .lastUpdateTime
                .toString()
        python.getModule("ankrimento.android").callAttr(
            "start",
            host,
            col.colDb.absolutePath,
            File(context.filesDir, "ankrimento/addons21").absolutePath,
            profile,
            buildId,
            // The app's language (AnkiDroid's own setting applies it to resources), as Anki's on desktop.
            ConfigurationCompat.getLocales(context.resources.configuration)[0]?.toLanguageTag() ?: "en",
        )
        facade = python.getModule("ankrimento.facade")
        ChangeManager.subscribe(operationListener)
        settings.syncFolder?.let { useSyncFolder(context, it) }
        syncHubCode = settings.syncHubCode
        Timber.i("Ankrimento started for profile '%s'", profile)
    }

    /** The profile's folder name in user_files/ and in the sync folder. */
    private fun profileStorageName(): String = requireFacade().callAttr("profile_name").toString()

    /** What a full backup holds: cards (with scheduling and media), Incremento files, and the archive's size. */
    data class FullBackup(
        val cards: Int,
        val files: Int,
        val bytes: Long,
    )

    /** Desktop's name for a full backup made today: `incremento_<profile>_full_backup_<date>.zip`. */
    @WorkerThread
    fun fullBackupName(): String = "incremento_${profileStorageName()}_full_backup_${LocalDate.now()}.zip"

    /** What a full backup holds, from upstream's restore pre-check. */
    data class RestoreCheck(
        val sourceProfile: String,
        val profileMismatch: Boolean,
        val cards: Int,
        val media: Int,
        val files: Int,
        val missingCovers: Int,
    )

    /** Checks the full backup [file] and stages it; nothing on the device changes yet. */
    @WorkerThread
    fun stageRestore(file: File): RestoreCheck {
        val report = JSONObject(requireFacade().callAttr("restore_stage", file.absolutePath).toString())
        return RestoreCheck(
            sourceProfile = report.getString("source_profile"),
            profileMismatch = report.getBoolean("profile_mismatch"),
            cards = report.getInt("cards"),
            media = report.getInt("media"),
            files = report.getInt("files"),
            missingCovers = report.getInt("missing_covers"),
        )
    }

    /** Puts the staged Incremento files in place (this device's are kept until [finishRestore]); returns the Anki package. */
    @WorkerThread
    fun restoreReplaceFiles(): String = requireFacade().callAttr("restore_replace_files").toString()

    /** After the collection restore: keeps the restored files, or puts this device's back. */
    @WorkerThread
    fun finishRestore(succeeded: Boolean) {
        requireFacade().callAttr("restore_finish", succeeded)
    }

    /** Drops a staged restore the user did not confirm. */
    @WorkerThread
    fun discardRestore() {
        requireFacade().callAttr("restore_discard")
    }

    /** Writes desktop's full backup ZIP of this profile (cards, media, Incremento's folder) to [file]. */
    @WorkerThread
    fun exportFullBackup(file: File): FullBackup {
        val result = JSONObject(requireFacade().callAttr("export_full_backup", file.absolutePath).toString())
        return FullBackup(result.getInt("cards"), result.getInt("files"), result.getLong("bytes"))
    }

    /**
     * The automatic backup to write next and those rotation removes, by upstream's rules,
     * from the backup folder's [files] (names and modification times).
     */
    @WorkerThread
    fun automaticBackupPlan(
        files: List<Pair<String, Long>>,
        keep: Int,
    ): Pair<String, List<String>> {
        val held = JSONArray(files.map { (name, modified) -> JSONArray().put(name).put(modified) })
        val plan = JSONObject(requireFacade().callAttr("automatic_backup_plan", held.toString(), keep).toString())
        val remove = plan.getJSONArray("remove")
        return plan.getString("write") to List(remove.length()) { remove.getString(it) }
    }

    /** What a sync did: see `facade.engine_sync`. */
    data class SyncResult(
        val sent: Int,
        val received: Int,
        val applied: Int,
        /** Records changed on two devices since they last synced; this device's value was kept. */
        val conflicts: Int,
        /** Files in the folder that were skipped because they could not be trusted. */
        val problems: List<String>,
        /** The time budget cut file transfer short: call again to continue. */
        val more: Boolean = false,
    ) {
        operator fun plus(next: SyncResult) =
            SyncResult(
                sent + next.sent,
                received + next.received,
                applied + next.applied,
                next.conflicts,
                (problems + next.problems).distinct(),
                next.more,
            )
    }

    val isSyncConfigured: Boolean
        get() = syncHubCode.isNotEmpty() || syncExchange != null

    private fun useSyncFolder(
        context: Context,
        folder: Uri,
    ) {
        syncExchange = SafExchange(context.applicationContext, folder)
        syncFolderLabel = "saf:$folder"
    }

    /** Use [folder] (picked with ACTION_OPEN_DOCUMENT_TREE) to sync Incremento data. */
    fun setSyncFolder(
        context: Context,
        folder: Uri,
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(folder, flags)
        AnkrimentoSettings(context).syncFolder = folder
        if (isStarted) useSyncFolder(context, folder)
    }

    /**
     * Use the hub paired with [code] (empty: no hub, back to the sync folder). The code is checked when it is
     * used: [sync] throws for one that is not a pairing code.
     */
    fun setSyncHub(
        context: Context,
        code: String,
    ) {
        AnkrimentoSettings(context).syncHubCode = code
        syncHubCode = code.trim()
    }

    /**
     * The device name and address ("phone", "192.168.1.20:8765") a hub pairing code names, or null when it is not
     * a pairing code. Only the code's structure is checked here; the key is never returned.
     */
    fun describeHubCode(code: String): Pair<String, String>? {
        val parts = code.trim().split('-')
        if (parts.size != 3 || parts[0] != "ihub1") return null
        return runCatching {
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)
            val fields = org.json.JSONArray(payload)
            if (fields.getInt(0) != 1) return null
            fields.getString(3) to "${fields.getString(1)}:${fields.getInt(2)}"
        }.getOrNull()
    }

    /**
     * Sync with the hub on the network if one is paired, else with the sync folder, through the sync engine; null
     * when neither is set. With [publishOnly] this device's changes are sent and nothing is received (quick, for a
     * closing screen). Throws when the hub cannot be reached or the library there is another Anki collection's.
     */
    @WorkerThread
    fun sync(
        publishOnly: Boolean = false,
        budgetSeconds: Double = NO_BUDGET,
    ): SyncResult? {
        val android = Python.getInstance().getModule("ankrimento.android")
        val result =
            if (syncHubCode.isNotEmpty()) {
                android.callAttr("sync_hub", syncHubCode, publishOnly, budgetSeconds)
            } else {
                val exchange = syncExchange ?: return null
                android.callAttr("sync_folder", exchange, syncFolderLabel, publishOnly, budgetSeconds)
            }
        val json = JSONObject(result.toString())
        val problems = json.getJSONArray("problems")
        return SyncResult(
            sent = json.getInt("sent"),
            received = json.getInt("received"),
            applied = json.getInt("applied"),
            conflicts = json.getInt("conflicts"),
            problems = List(problems.length()) { problems.getString(it) },
            more = json.optBoolean("more", false),
        )
    }

    /**
     * Sync in slices of about [SLICE_SECONDS], giving other work on the collection a turn between them, so a large
     * first sync (many documents) does not hold everything up. [onProgress] hears about each slice that left more to do,
     * with the slice's number.
     */
    suspend fun syncInSlices(onProgress: (SyncResult, Int) -> Unit = { _, _ -> }): SyncResult? {
        var total: SyncResult? = null
        repeat(MAX_SLICES) { index ->
            val slice = CollectionManager.withCol { sync(budgetSeconds = SLICE_SECONDS) } ?: return total
            total = total?.plus(slice) ?: slice
            if (!slice.more) return total
            onProgress(total, index + 1)
            yield()
        }
        return total
    }

    /**
     * Desktop Anki fires `undo_state_did_change` after every operation, and
     * Incremento uses it to follow undo/redo of its scheduling. Do the same
     * for every operation AnkiDroid publishes, on the collection queue.
     * Held here because [ChangeManager] keeps subscribers weakly.
     */
    private val operationListener =
        object : ChangeManager.Subscriber {
            override fun opExecuted(
                changes: OpChanges,
                handler: Any?,
            ) {
                operationScope.launch {
                    runCatching { CollectionManager.withCol { requireFacade().callAttr("undo_state_changed") } }
                        .onFailure { Timber.w(it, "Incremento undo reconciliation failed") }
                }
            }
        }

    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Work that outlives a reader's screen: saving what its page reported
     * (reading position, highlights). A reader closed right after a scroll
     * still has its last position saved.
     */
    val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Start a session with Incremento's saved scheduler settings, as desktop's
     * "Start Incremental Learning" does. Returns at once; [Event.EnterReview]
     * follows when the session deck is ready, or an [Event.Message].
     */
    fun startSession() {
        requireFacade().callAttr("start_session")
    }

    /** The filtered deck sessions fill, as upstream names it. */
    @WorkerThread
    fun sessionDeckName(): String = requireFacade().callAttr("session_deck_name").toString()

    /**
     * The advanced session options, as upstream reads them: `topics_filter`, `items_filter`,
     * `tags` (tag and weight), `other_cards`, `content_types` (type, enabled, weight),
     * `media_outside_tags`, `strict` and `scope`.
     */
    @WorkerThread
    fun sessionOptions(): JSONObject = JSONObject(requireFacade().callAttr("session_options").toString())

    /** Saves some of the advanced session options; returns them all. Throws for a filter Anki can't search. */
    @WorkerThread
    fun setSessionOptions(changes: JSONObject): JSONObject =
        JSONObject(requireFacade().callAttr("set_session_options", changes.toString()).toString())

    /**
     * Upstream's Search ALL with the saved filters: `sections`, each with a `heading` and `groups`
     * (a document `title`, or none, and its `items`: `label`, `snippet`, and where it opens:
     * `kind` "pdf", "epub" or "card", `card_id`, `position`).
     */
    @WorkerThread
    fun searchAll(query: String): JSONObject = JSONObject(requireFacade().callAttr("search_all", query).toString())

    /** Desktop's Search ALL filters and whether each is on. */
    @WorkerThread
    fun searchAllFilters(): JSONObject = JSONObject(requireFacade().callAttr("search_all_filters").toString())

    @WorkerThread
    fun setSearchAllFilter(
        name: String,
        enabled: Boolean,
    ) {
        requireFacade().callAttr("set_search_all_filter", name, enabled)
    }

    /** The PDF's text index for find: "current", "empty" (no text found), "stale" or "missing". */
    @WorkerThread
    fun pdfIndexState(cardId: Long): String = requireFacade().callAttr("pdf_index_state", cardId).toString()

    /** Indexes the page texts the viewer extracted, through upstream's indexer. */
    @WorkerThread
    fun pdfIndexPages(
        cardId: Long,
        pages: List<String>,
    ) {
        requireFacade().callAttr("pdf_index_pages", cardId, JSONArray(pages).toString())
    }

    /** A page matching a search: its number, a short excerpt, and the longer one the viewer highlights. */
    data class PdfSearchHit(
        val page: Int,
        val snippet: String,
        val excerpt: String,
    )

    /** The pages of the PDF matching [query], in page order (upstream's search of the text index). */
    @WorkerThread
    fun pdfSearch(
        cardId: Long,
        query: String,
    ): List<PdfSearchHit> {
        val hits = JSONArray(requireFacade().callAttr("pdf_search", cardId, query).toString())
        return List(hits.length()) { index ->
            val hit = hits.getJSONObject(index)
            PdfSearchHit(hit.getInt("page"), hit.getString("snippet"), hit.getString("excerpt"))
        }
    }

    /** A PDF's bookmarks and highlights, in page order. */
    data class PdfMarks(
        val bookmarks: List<MarkBookmark>,
        val highlights: List<MarkHighlight>,
    )

    @WorkerThread
    fun pdfMarks(cardId: Long): PdfMarks {
        val marks = JSONObject(requireFacade().callAttr("pdf_marks", cardId).toString())
        val bookmarks = marks.getJSONArray("bookmarks")
        val highlights = marks.getJSONArray("highlights")
        return PdfMarks(
            List(bookmarks.length()) { index ->
                val b = bookmarks.getJSONObject(index)
                MarkBookmark(b.getString("id"), b.getInt("page"), b.optString("label"), b.optString("comment"))
            },
            List(highlights.length()) { index ->
                val h = highlights.getJSONObject(index)
                MarkHighlight(
                    h.getString("id"),
                    h.getInt("page"),
                    h.optString("text"),
                    h.optString("color"),
                    h.optString("note"),
                    style = h.optString("style", "highlight"),
                )
            },
        )
    }

    /** Each of these changes a bookmark or highlight and returns the script that updates the viewer. */
    @WorkerThread
    fun pdfAddBookmark(
        cardId: Long,
        page: Int,
    ): String = requireFacade().callAttr("pdf_add_bookmark", cardId, page).toString()

    @WorkerThread
    fun pdfDeleteBookmark(
        cardId: Long,
        bookmarkId: String,
    ): String = requireFacade().callAttr("pdf_delete_bookmark", cardId, bookmarkId).toString()

    @WorkerThread
    fun pdfHighlightColor(
        cardId: Long,
        highlightId: String,
        color: String,
    ): String = requireFacade().callAttr("pdf_highlight_color", cardId, highlightId, color).toString()

    /** The viewer's card button on a highlight: its card, or a draft (the sheet follows), or desktop's message. */
    @WorkerThread
    fun pdfHighlightCard(
        cardId: Long,
        highlightId: String,
    ): JSONObject = JSONObject(requireFacade().callAttr("pdf_highlight_card", cardId, highlightId).toString())

    /** The browser search for the cards made from this PDF; its "message" when there are none. */
    @WorkerThread
    fun pdfDocumentCards(cardId: Long): JSONObject = JSONObject(requireFacade().callAttr("pdf_document_cards", cardId).toString())

    /** The browser search for notes the viewer lists ([scope] "page"); its "message" when there are none. */
    @WorkerThread
    fun pdfNoteCards(
        noteIds: String,
        scope: String,
    ): JSONObject = JSONObject(requireFacade().callAttr("pdf_note_cards", noteIds, scope).toString())

    @WorkerThread
    fun noteFirstCard(noteId: Long): Long = requireFacade().callAttr("note_first_card", noteId).toLong()

    /** The viewer's Add Card: the extract sheet for this PDF, from the page being read. */
    @WorkerThread
    fun pdfNewCard(cardId: Long) {
        requireFacade().callAttr("pdf_new_card", cardId)
    }

    /** The highlights without a card, for desktop's "cards from highlights". */
    @WorkerThread
    fun pdfHighlightCardsPlan(cardId: Long): HighlightCardsPlan {
        val plan = JSONObject(requireFacade().callAttr("pdf_highlight_cards_plan", cardId).toString())
        val rows = plan.getJSONArray("rows")
        val types = plan.getJSONArray("note_types")
        return HighlightCardsPlan(
            rows =
                List(rows.length()) { index ->
                    rows.getJSONObject(index).let { HighlightRow(it.getString("id"), it.optInt("page"), it.optString("text")) }
                },
            message = plan.optString("message"),
            noteTypes = List(types.length()) { types.getString(it) },
            noteType = plan.optString("note_type"),
            isTopic = plan.optString("kind") == "topic",
        )
    }

    /** One card per highlight in [highlightIds]; returns desktop's summary line and the viewer's refresh script. */
    @WorkerThread
    fun pdfHighlightCardsCreate(
        cardId: Long,
        highlightIds: List<String>,
        noteType: String,
        isTopic: Boolean,
    ): Pair<String, String> {
        val ids = org.json.JSONArray(highlightIds).toString()
        val kind = if (isTopic) "topic" else "item"
        val summary = JSONObject(requireFacade().callAttr("pdf_highlight_cards_create", cardId, ids, noteType, kind).toString())
        return summary.optString("message") to summary.optString("script")
    }

    /** Desktop's reading limit dialog for this PDF, with its words. */
    @WorkerThread
    fun pdfLimitSettings(cardId: Long): PageLimit {
        val data = JSONObject(requireFacade().callAttr("pdf_limit_settings", cardId).toString())
        val labels = data.getJSONObject("labels")
        val modes = data.getJSONArray("modes")
        return PageLimit(
            enabled = data.optBoolean("enabled"),
            pages = data.optInt("daily_page_limit", 10),
            mode = data.optString("enforcement_mode", "warning"),
            modes = List(modes.length()) { modes.getJSONObject(it).let { m -> m.getString("key") to m.getString("label") } },
            title = labels.optString("title"),
            enabledLabel = labels.optString("enabled"),
            limitLabel = labels.optString("limit"),
            hint = labels.optString("hint"),
        )
    }

    /** Saves the limit; returns the viewer's script and desktop's message. */
    @WorkerThread
    fun pdfSaveLimit(
        cardId: Long,
        enabled: Boolean,
        pages: Int,
        mode: String,
    ): Pair<String, String> {
        val saved = JSONObject(requireFacade().callAttr("pdf_save_limit", cardId, enabled, pages, mode).toString())
        return saved.optString("script") to saved.optString("message")
    }

    /** A highlight without a card, offered by "cards from highlights". */
    data class HighlightRow(
        val id: String,
        val page: Int,
        val text: String,
    )

    data class HighlightCardsPlan(
        val rows: List<HighlightRow>,
        val message: String,
        val noteTypes: List<String>,
        val noteType: String,
        val isTopic: Boolean,
    )

    /** A PDF's reading limit and the dialog's words (desktop's). */
    data class PageLimit(
        val enabled: Boolean,
        val pages: Int,
        val mode: String,
        val modes: List<Pair<String, String>>,
        val title: String,
        val enabledLabel: String,
        val limitLabel: String,
        val hint: String,
    )

    /** Answers the PDF viewer's colour button with [selected] ("" for none); returns the viewer's script. */
    @WorkerThread
    fun pdfChooseColor(
        cardId: Long,
        data: String,
        selected: String,
    ): String = requireFacade().callAttr("pdf_choose_color", cardId, data, selected).toString()

    @WorkerThread
    fun pdfHighlightNote(
        cardId: Long,
        highlightId: String,
        note: String,
    ): String = requireFacade().callAttr("pdf_highlight_note", cardId, highlightId, note).toString()

    @WorkerThread
    fun pdfDeleteHighlight(
        cardId: Long,
        highlightId: String,
    ): String = requireFacade().callAttr("pdf_delete_highlight", cardId, highlightId).toString()

    /** Desktop's saved session presets, and the one the current settings came from. */
    data class SessionPresets(
        val selected: String?,
        val names: List<String>,
    )

    @WorkerThread
    fun sessionPresets(): SessionPresets {
        val presets = JSONObject(requireFacade().callAttr("session_presets").toString())
        val names = presets.getJSONArray("names")
        return SessionPresets(
            selected = presets.optString("selected").takeIf { !presets.isNull("selected") && it.isNotEmpty() },
            names = List(names.length()) { names.getString(it) },
        )
    }

    /** Applies a preset's settings; null keeps the current settings without a preset. */
    @WorkerThread
    fun loadSessionPreset(name: String?) {
        requireFacade().callAttr("load_session_preset", name)
    }

    /** Saves the current settings as a new preset, which becomes the selected one. */
    @WorkerThread
    fun addSessionPreset(name: String) {
        requireFacade().callAttr("add_session_preset", name)
    }

    /** Updates the selected preset with the current settings. */
    @WorkerThread
    fun saveSessionPreset() {
        requireFacade().callAttr("save_session_preset")
    }

    @WorkerThread
    fun renameSessionPreset(
        oldName: String,
        newName: String,
    ) {
        requireFacade().callAttr("rename_session_preset", oldName, newName)
    }

    @WorkerThread
    fun deleteSessionPreset(name: String) {
        requireFacade().callAttr("delete_session_preset", name)
    }

    /** Reviewer hook: a card's question is on screen. Records whether it is a topic, and whether it has a reader. */
    @WorkerThread
    fun questionShown(cardId: Long) {
        if (!isStarted) return
        requireFacade().callAttr("question_shown", cardId)
        shownTopicCardId = if (requireFacade().callAttr("is_topic", cardId).toBoolean()) cardId else null
        shownReaderCardId = if (cardReader(cardId).isNotEmpty()) cardId else null
    }

    /** The card on screen in the reviewer, if it opens in an Incremento reader. */
    @Volatile
    var shownReaderCardId: Long? = null
        private set

    /** The card on screen in the reviewer, if Incremento treats it as a topic. */
    @Volatile
    var shownTopicCardId: Long? = null
        private set

    /** Mark the topic done: suspend it and tag its note (desktop's Done). Returns a message. */
    @WorkerThread
    fun topicDone(cardId: Long): String = requireFacade().callAttr("topic_done", cardId).toString()

    /** Show the topic again only after [months] (3, 6 or 12). Returns a message. */
    @WorkerThread
    fun topicRevisit(
        cardId: Long,
        months: Int,
    ): String = requireFacade().callAttr("topic_revisit", cardId, months).toString()

    /** Show the topic again on [isoDate] (yyyy-mm-dd), desktop's "Revisit in… → Choose date…". Returns a message. */
    @WorkerThread
    fun topicRevisitOn(
        cardId: Long,
        isoDate: String,
    ): String = requireFacade().callAttr("topic_revisit_on", cardId, isoDate).toString()

    /** Postpone the topic for this session or the configured minutes. Returns a message. */
    @WorkerThread
    fun topicPostpone(cardId: Long): String = requireFacade().callAttr("topic_postpone", cardId).toString()

    @WorkerThread
    fun priority(cardId: Long): Double = requireFacade().callAttr("get_priority", cardId).toDouble()

    @WorkerThread
    fun setPriority(
        cardId: Long,
        priority: Double,
    ) {
        requireFacade().callAttr("set_priority", cardId, priority)
    }

    /** Reviewer hook: a card's answer is on screen. */
    @WorkerThread
    fun answerShown(cardId: Long) {
        if (isStarted) requireFacade().callAttr("answer_shown", cardId)
    }

    /** Reviewer hook: the reviewer is closing; also pushes to the sync folder. */
    fun reviewerClosed() {
        if (!isStarted) return
        operationScope.launch {
            runCatching { CollectionManager.withCol { requireFacade().callAttr("reviewer_closed") } }
                .onFailure { Timber.w(it, "Incremento reviewer_closed failed") }
            runCatching { CollectionManager.withCol { sync(publishOnly = true) } }
                .onFailure { Timber.w(it, "Incremento sync on close failed") }
        }
    }

    /**
     * AnkiDroid has just synced the collection with AnkiWeb. Incremento data for cards that were still unknown
     * to this collection (or to AnkiWeb) waits for exactly that, so run the sync engine now. Quiet: the user did
     * not ask for it, so a failure is only logged.
     */
    fun collectionSynced() {
        if (!isStarted || !isSyncConfigured) return
        operationScope.launch {
            runCatching { syncInSlices() }
                .onSuccess { Timber.i("Incremento sync after the collection sync: %s", it) }
                .onFailure { Timber.w(it, "Incremento sync after the collection sync failed") }
        }
    }

    /** The reader for [cardId]: a document's ("pdf", "epub", …), "text" for any other topic, "" for an item. */
    @WorkerThread
    fun cardReader(cardId: Long): String = if (isStarted) requireFacade().callAttr("card_reader", cardId).toString() else ""

    /** A document in the library: [position] is where reading stopped ("p. 12", "ch. 3", "2:05"), [done] a finished topic. */
    data class LibraryEntry(
        val cardId: Long,
        val kind: String,
        val title: String,
        val priority: Double,
        val position: String,
        val done: Boolean,
        /** The cover image file desktop's bookshelf shows (PDF, EPUB), or "". */
        val cover: String = "",
        /** The PDF page or EPUB chapter reached (0: none), for [position] in the app's language. */
        val page: Int = 0,
        val chapter: Int = 0,
        /** The note's id, which encodes when the document was added (desktop's "Last added" order). */
        val noteId: Long = 0,
        /** Desktop's bookshelf figures for a PDF or EPUB (null for other kinds): what was viewed and marked read. */
        val viewedPercent: Double? = null,
        val markedPercent: Double? = null,
        val coverageLabel: String = "",
    )

    /** Desktop's review buttons (`group`, `done`, `postpone`, `extract`) and whether each is shown. */
    @WorkerThread
    fun reviewButtons(): JSONObject = JSONObject(requireFacade().callAttr("review_buttons").toString())

    @WorkerThread
    fun setReviewButton(
        name: String,
        visible: Boolean,
    ) {
        requireFacade().callAttr("set_review_button", name, visible)
    }

    /** The Incremento settings shown on mobile (upstream's config keys), as JSON. */
    @WorkerThread
    fun settingsValues(): JSONObject = JSONObject(requireFacade().callAttr("settings_values").toString())

    /** Saves one setting; returns the value upstream kept after normalizing it. */
    @WorkerThread
    fun setSetting(
        key: String,
        value: Any,
    ): Any {
        val json =
            JSONObject()
                .put("v", value)
                .toString()
                .removePrefix("{\"v\":")
                .removeSuffix("}")
        val kept = requireFacade().callAttr("set_setting", key, json).toString()
        return JSONObject("{\"v\":$kept}").get("v")
    }

    /** Every Incremento document, as desktop's bookshelf lists them. */
    @WorkerThread
    fun library(): List<LibraryEntry> {
        val entries = JSONArray(requireFacade().callAttr("library").toString())
        return List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            LibraryEntry(
                cardId = entry.getLong("card_id"),
                kind = entry.getString("kind"),
                title = displayTitle(entry.getString("title")),
                priority = entry.getDouble("priority"),
                position = entry.getString("position"),
                done = entry.getBoolean("done"),
                cover = entry.optString("cover"),
                page = entry.optInt("page"),
                chapter = entry.optInt("chapter"),
                noteId = entry.optLong("note_id"),
                viewedPercent = if (entry.isNull("viewed_percent")) null else entry.optDouble("viewed_percent"),
                markedPercent = if (entry.isNull("marked_percent")) null else entry.optDouble("marked_percent"),
                coverageLabel = entry.optString("coverage_label"),
            )
        }
    }

    /** The title the knowledge tree shows for [cardId]. */
    @WorkerThread
    fun cardTitle(cardId: Long): String = displayTitle(requireFacade().callAttr("card_title", cardId).toString())

    /**
     * A title as text: upstream's titles are fields with their tags removed but
     * entities kept ("&#9654; 0:19 – …" for a video extract).
     */
    private fun displayTitle(title: String): String =
        android.text.Html
            .fromHtml(title, android.text.Html.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()

    /** Add a PDF file as an Incremento PDF topic card; returns the card id. */
    @WorkerThread
    fun importPdf(
        path: String,
        title: String,
    ): Long = requireFacade().callAttr("import_pdf", path, title).toLong()

    /** Add an EPUB file as an Incremento EPUB topic card; returns the card id. */
    @WorkerThread
    fun importEpub(
        path: String,
        title: String,
    ): Long = requireFacade().callAttr("import_epub", path, title).toLong()

    /** A video file from the device as desktop's Incremento Video topic, kept as it is (no re-encoding). */
    @WorkerThread
    fun importLocalVideo(
        path: String,
        title: String,
    ): Long = requireFacade().callAttr("import_local_video", path, title).toLong()

    /** An audio file as desktop's Incremento Audio topic (MP3, M4A, AAC or WAV), played by its card's own player. */
    @WorkerThread
    fun importAudio(
        path: String,
        title: String,
    ): Long = requireFacade().callAttr("import_audio", path, title).toLong()

    /** A cloze from the reader's selection: [selected] hidden in its sentence ([before], [after]); the extract sheet follows. */
    @WorkerThread
    fun extractCloze(
        cardId: Long,
        before: String,
        selected: String,
        after: String,
    ) {
        requireFacade().callAttr("extract_cloze", cardId, before, selected, after)
    }

    /** A Markdown file as a study document, read in the EPUB reader (desktop's Markdown import). */
    @WorkerThread
    fun importMarkdown(
        path: String,
        title: String,
    ): Long = requireFacade().callAttr("import_markdown", path, title).toLong()

    /** A chapter of an EPUB topic, extracted to [root]; [path] is the chapter file. */
    data class EpubSection(
        val root: String,
        val path: String,
        val index: Int,
        val count: Int,
        val nonce: String,
    )

    /** The chapter to show: [sectionIndex], or the saved one when null. */
    @WorkerThread
    fun epubOpen(
        cardId: Long,
        sectionIndex: Int? = null,
    ): EpubSection {
        val json = JSONObject(requireFacade().callAttr("epub_open", cardId, sectionIndex ?: -1).toString())
        return EpubSection(
            root = json.getString("root"),
            path = json.getString("path"),
            index = json.getInt("section_index"),
            count = json.getInt("section_count"),
            nonce = json.getString("nonce"),
        )
    }

    /** Desktop's EPUB page script for a loaded chapter; [scrollRatio] null restores the saved position. */
    @WorkerThread
    fun epubScript(
        cardId: Long,
        section: EpubSection,
        scrollRatio: Double?,
        searchQuery: String = "",
        linkFragment: String = "",
    ): String =
        requireFacade()
            .callAttr("epub_script", cardId, section.index, section.nonce, scrollRatio ?: -1.0, searchQuery, linkFragment)
            .toString()

    /** The book's highlights in book order; [MarkHighlight.page] is the chapter, [MarkHighlight.place] its title. */
    @WorkerThread
    fun epubHighlights(cardId: Long): List<MarkHighlight> {
        val highlights = JSONObject(requireFacade().callAttr("epub_marks", cardId).toString()).getJSONArray("highlights")
        return List(highlights.length()) { index ->
            val h = highlights.getJSONObject(index)
            MarkHighlight(
                h.getString("id"),
                h.getInt("section"),
                h.optString("text"),
                h.optString("color"),
                h.optString("note"),
                h.optString("title"),
                h.optString("style", "highlight"),
            )
        }
    }

    /** Each of these changes a highlight and returns the script that updates the page. */
    @WorkerThread
    fun epubHighlightColor(
        cardId: Long,
        highlightId: String,
        color: String,
    ): String = requireFacade().callAttr("epub_highlight_color", cardId, highlightId, color).toString()

    @WorkerThread
    fun epubHighlightStyle(
        cardId: Long,
        highlightId: String,
        style: String,
    ): String = requireFacade().callAttr("epub_highlight_style", cardId, highlightId, style).toString()

    @WorkerThread
    fun pdfHighlightStyle(
        cardId: Long,
        highlightId: String,
        style: String,
    ): String = requireFacade().callAttr("pdf_highlight_style", cardId, highlightId, style).toString()

    @WorkerThread
    fun pdfBookmarkComment(
        cardId: Long,
        bookmarkId: String,
        comment: String,
    ): String = requireFacade().callAttr("pdf_bookmark_comment", cardId, bookmarkId, comment).toString()

    @WorkerThread
    fun epubHighlightNote(
        cardId: Long,
        highlightId: String,
        note: String,
    ): String = requireFacade().callAttr("epub_highlight_note", cardId, highlightId, note).toString()

    @WorkerThread
    fun epubDeleteHighlight(
        cardId: Long,
        highlightId: String,
        sectionIndex: Int,
    ): String = requireFacade().callAttr("epub_delete_highlight", cardId, highlightId, sectionIndex).toString()

    /** A chapter matching a search: its index, title and an excerpt. */
    data class EpubSearchHit(
        val section: Int,
        val title: String,
        val snippet: String,
    )

    /** The chapters of the book matching [query], in book order (upstream's search of its text index). */
    @WorkerThread
    fun epubSearch(
        cardId: Long,
        query: String,
    ): List<EpubSearchHit> {
        val hits = JSONArray(requireFacade().callAttr("epub_search", cardId, query).toString())
        return List(hits.length()) { index ->
            val hit = hits.getJSONObject(index)
            EpubSearchHit(hit.getInt("section"), hit.optString("title"), hit.optString("snippet"))
        }
    }

    /** A segment of a local video, cut into [clip], as a new card; the extract sheet follows. */
    @WorkerThread
    fun videoClip(
        cardId: Long,
        start: Double,
        end: Double,
        clip: java.io.File,
        duration: Double,
    ) {
        requireFacade().callAttr("video_clip", cardId, start, end, clip.path, duration)
    }

    /** A region of the chapter as a new card's image; the extract sheet follows. */
    @WorkerThread
    fun epubSnapshot(
        cardId: Long,
        sectionIndex: Int,
        image: String,
    ) {
        requireFacade().callAttr("epub_snapshot", cardId, sectionIndex, image)
    }

    /** Apply a page-script message; returns the chapter to load next and its scroll ratio, if any. */
    @WorkerThread
    fun epubMessage(
        cardId: Long,
        sectionIndex: Int,
        message: String,
    ): EpubAction? {
        val json = JSONObject(requireFacade().callAttr("epub_message", cardId, sectionIndex, message).toString())
        json.optJSONObject("navigate")?.let { return EpubAction.Navigate(it.getInt("section_index"), it.getDouble("scroll_ratio")) }
        json.optJSONObject("coverage")?.let { figures -> return coverageOf(figures)?.let { EpubAction.Viewed(it) } }
        val link = json.optJSONObject("link") ?: return null
        return when (link.optString("kind")) {
            "external" -> EpubAction.OpenWeb(link.getString("url"))
            "internal" ->
                EpubAction.FollowLink(
                    link.getInt("section_index"),
                    link.optString("fragment"),
                    link.optString("note"),
                    link.optDouble("from_ratio", 0.0),
                )
            else -> EpubAction.Blocked(link.optString("message"))
        }
    }

    /** Reading coverage of a PDF or EPUB: desktop's label, "Viewed 40% · Marked read 10%". */
    data class Coverage(
        val label: String,
        val viewedPercent: Double?,
        val markedPercent: Double?,
    )

    private fun coverageOf(json: JSONObject): Coverage? =
        if (json.optString("label").isEmpty()) {
            null
        } else {
            Coverage(
                json.getString("label"),
                if (json.isNull("viewed_percent")) null else json.optDouble("viewed_percent"),
                if (json.isNull("marked_percent")) null else json.optDouble("marked_percent"),
            )
        }

    /** The card an extract came from: [available] false when it is gone; [fields] are (name, sanitised markup). */
    data class ParentContext(
        val cardId: Long,
        val title: String,
        val available: Boolean,
        val fields: List<Pair<String, String>>,
    )

    /** The parent of [cardId] for the Parent sheet, or null when the card has none. */
    @WorkerThread
    fun parentContext(cardId: Long): ParentContext? {
        val json = JSONObject(requireFacade().callAttr("parent_context", cardId).toString())
        if (!json.has("card_id")) return null
        val fields = json.getJSONArray("fields")
        return ParentContext(
            json.getLong("card_id"),
            displayTitle(json.getString("title")),
            json.getBoolean("available"),
            List(fields.length()) { fields.getJSONObject(it).let { field -> field.getString("name") to field.getString("html") } },
        )
    }

    /** What was viewed and marked read of a PDF or EPUB, or null for any other document. */
    @WorkerThread
    fun coverage(cardId: Long): Coverage? = coverageOf(JSONObject(requireFacade().callAttr("coverage_summary", cardId).toString()))

    /**
     * Marks a PDF page range (0-based [start] up to [end], [position] 0) or an EPUB passage of chapter [position]
     * read or unread; an EPUB [end] of 0 is the whole chapter. Returns the new figures.
     */
    @WorkerThread
    fun markCoverage(
        cardId: Long,
        read: Boolean,
        position: Int,
        start: Int,
        end: Int,
    ): Coverage? =
        coverageOf(
            JSONObject(
                requireFacade().callAttr("coverage_mark", cardId, if (read) "mark" else "unmark", position, start, end).toString(),
            ),
        )

    /** What the reader does after a page message: another chapter, or a tapped link. */
    sealed interface EpubAction {
        /** The figures after a passage came into view. */
        data class Viewed(
            val coverage: Coverage,
        ) : EpubAction

        data class Navigate(
            val section: Int,
            val scrollRatio: Double,
        ) : EpubAction

        data class OpenWeb(
            val url: String,
        ) : EpubAction

        /** A link inside the book: its chapter and anchor, the note's text when it is a footnote. */
        data class FollowLink(
            val section: Int,
            val fragment: String,
            val note: String,
            val fromRatio: Double,
        ) : EpubAction

        data class Blocked(
            val message: String,
        ) : EpubAction
    }

    /**
     * What desktop keeps per book: [textScale] (0.7 to 2.2) and [appearance]
     * ("original", "dark", "night"); with the chapter titles.
     */
    data class EpubReading(
        val textScale: Double,
        val appearance: String,
        val contents: List<String>,
    )

    @WorkerThread
    fun epubReading(cardId: Long): EpubReading {
        val json = JSONObject(requireFacade().callAttr("epub_reading", cardId).toString())
        val contents = json.getJSONArray("contents")
        return EpubReading(
            textScale = json.getDouble("text_scale"),
            appearance = json.getString("appearance"),
            contents = List(contents.length()) { contents.getString(it) },
        )
    }

    /** Stores the book's text scale as desktop's text size buttons do; returns the scale kept. */
    @WorkerThread
    fun epubSetTextScale(
        cardId: Long,
        scale: Double,
    ): Double = requireFacade().callAttr("epub_set_text_scale", cardId, scale).toDouble()

    @WorkerThread
    fun epubSetAppearance(
        cardId: Long,
        mode: String,
    ) {
        requireFacade().callAttr("epub_set_appearance", cardId, mode)
    }

    /** Starts an extract from the video at [seconds], as desktop's "Add Card at this point". */
    @WorkerThread
    fun videoExtract(
        cardId: Long,
        seconds: Double,
    ) {
        requireFacade().callAttr("video_extract", cardId, seconds)
    }

    /**
     * A web page as a document to read (clean text, read like an EPUB): upstream's
     * page-to-Markdown fetch and Markdown document import. Returns the card and its title.
     */
    @WorkerThread
    fun importWebDocument(url: String): Pair<Long, String> {
        val added = JSONObject(requireFacade().callAttr("import_web_document", url).toString())
        return added.getLong("card_id") to added.getString("title")
    }

    /**
     * A web page saved for offline reading as desktop's "Webpage as a document" does: its own appearance in a
     * self-contained snapshot ([website] true), or an editable reading copy. Returns the card and its title.
     */
    @WorkerThread
    fun importWebSnapshot(
        url: String,
        website: Boolean,
    ): Pair<Long, String> {
        val added = JSONObject(requireFacade().callAttr("import_web_snapshot", url, if (website) "html" else "markdown").toString())
        return added.getLong("card_id") to added.getString("title")
    }

    /** Whether [url] is a video Incremento plays (YouTube, Vimeo) rather than a page. */
    fun isVideoLink(url: String): Boolean = VIDEO_LINK.containsMatchIn(url)

    private val VIDEO_LINK = Regex("""(?:^|//)(?:www\.|m\.)?(?:youtube\.com|youtu\.be|vimeo\.com)/""", RegexOption.IGNORE_CASE)

    /** Add a shared link: a video link as a video topic, anything else as a web topic. */
    @WorkerThread
    fun importLink(
        url: String,
        title: String,
    ): Long = JSONObject(requireFacade().callAttr("import_link", url, title).toString()).getLong("card_id")

    /** The video's bookmarks by time, as the bookmarks sheet shows them ([MarkBookmark.page] holds the second). */
    @WorkerThread
    fun videoBookmarks(cardId: Long): List<MarkBookmark> = videoMarks(requireFacade().callAttr("video_bookmarks", cardId))

    @WorkerThread
    fun videoAddBookmark(
        cardId: Long,
        seconds: Double,
    ): List<MarkBookmark> = videoMarks(requireFacade().callAttr("video_add_bookmark", cardId, seconds))

    @WorkerThread
    fun videoDeleteBookmark(
        cardId: Long,
        bookmarkId: String,
    ): List<MarkBookmark> = videoMarks(requireFacade().callAttr("video_delete_bookmark", cardId, bookmarkId))

    @WorkerThread
    fun videoBookmarkComment(
        cardId: Long,
        bookmarkId: String,
        comment: String,
    ): List<MarkBookmark> = videoMarks(requireFacade().callAttr("video_bookmark_comment", cardId, bookmarkId, comment))

    private fun videoMarks(result: PyObject): List<MarkBookmark> {
        val rows = JSONArray(result.toString())
        return List(rows.length()) { index ->
            rows.getJSONObject(index).let {
                MarkBookmark(it.getString("id"), it.getDouble("seconds").toInt(), it.getString("label"), it.getString("comment"))
            }
        }
    }

    /** A subtitle cue: from [start] to [end] seconds. */
    data class Cue(
        val start: Double,
        val end: Double,
        val text: String,
    )

    /** One of a video's subtitle slots: its label (the file's name) and cues; empty when there is none. */
    data class SubtitleTrack(
        val label: String,
        val cues: List<Cue>,
    )

    /** Desktop's paired subtitles: the language learnt ([target]) and one known ([reference]). */
    data class Subtitles(
        val target: SubtitleTrack,
        val reference: SubtitleTrack,
    ) {
        val isEmpty get() = target.cues.isEmpty() && reference.cues.isEmpty()
    }

    @WorkerThread
    fun videoSubtitles(cardId: Long): Subtitles {
        val data = JSONObject(requireFacade().callAttr("video_subtitles", cardId).toString())

        fun track(slot: String): SubtitleTrack {
            val json = data.getJSONObject(slot)
            val cues = json.getJSONArray("cues")
            return SubtitleTrack(
                json.optString("label"),
                List(cues.length()) { index ->
                    cues.getJSONObject(index).let { Cue(it.getDouble("start"), it.getDouble("end"), it.optString("text")) }
                },
            )
        }
        return Subtitles(track("target"), track("reference"))
    }

    /** A subtitle file (SRT or WebVTT) at [path] for [slot] ("target" or "reference"), labelled [label]. */
    @WorkerThread
    fun videoAttachSubtitle(
        cardId: Long,
        slot: String,
        path: String,
        label: String,
    ) {
        requireFacade().callAttr("video_attach_subtitle", cardId, slot, path, label)
    }

    @WorkerThread
    fun videoClearSubtitle(
        cardId: Long,
        slot: String,
    ) {
        requireFacade().callAttr("video_clear_subtitle", cardId, slot)
    }

    /** What a video topic plays: [kind] "local" ([path]), "youtube" ([videoId]), "vimeo" (its player page, [url]) or "none" ([url]). */
    data class Video(
        val kind: String,
        val path: String,
        val videoId: String,
        val url: String,
        val position: Double,
    )

    @WorkerThread
    fun videoOpen(cardId: Long): Video {
        val json = JSONObject(requireFacade().callAttr("video_open", cardId).toString())
        return Video(
            kind = json.getString("kind"),
            path = json.optString("path"),
            videoId = json.optString("video_id"),
            url = json.optString("url"),
            position = json.getDouble("position"),
        )
    }

    /** Save the playback position and add [watchedSeconds] of observed playback. */
    @WorkerThread
    fun videoProgress(
        cardId: Long,
        position: Double,
        watchedSeconds: Double,
    ) {
        requireFacade().callAttr("video_progress", cardId, position, watchedSeconds)
    }

    /** A writing topic's Markdown text. */
    @WorkerThread
    fun writingOpen(cardId: Long): String = JSONObject(requireFacade().callAttr("writing_open", cardId).toString()).getString("text")

    /** One of desktop's writing formatting buttons: its [kind], [label] and tooltip ([description]). */
    data class WritingButton(
        val kind: String,
        val label: String,
        val description: String,
    )

    @WorkerThread
    fun writingToolbar(): List<WritingButton> {
        val rows = JSONArray(requireFacade().callAttr("writing_toolbar").toString())
        return List(rows.length()) { index ->
            rows.getJSONObject(index).let { WritingButton(it.getString("kind"), it.getString("label"), it.getString("description")) }
        }
    }

    /** What a formatting button puts in place of [selected]. */
    @WorkerThread
    fun writingTransform(
        kind: String,
        selected: String,
    ): String = requireFacade().callAttr("writing_transform", kind, selected).toString()

    /** Desktop's Markdown preview of [text], as HTML. */
    @WorkerThread
    fun writingPreview(text: String): String = requireFacade().callAttr("writing_preview", text).toString()

    /** Save a writing topic as desktop does (backups, word stats); returns the word count. */
    @WorkerThread
    fun writingSave(
        cardId: Long,
        text: String,
    ): Int = requireFacade().callAttr("writing_save", cardId, text).toInt()

    /** A new writing topic; returns its card id. */
    @WorkerThread
    fun createWriting(title: String): Long = requireFacade().callAttr("create_writing", title).toLong()

    /** A passage quoted in a writing topic, or the passage a writing topic quotes from a document's page or chapter. */
    data class WritingExcerpt(
        val anchor: String,
        /** "pdf" or "epub". */
        val kind: String,
        val cardId: Long,
        val page: Int,
        val section: Int,
        val offset: Int,
        val quote: String,
        /** Desktop's wording for where it is from ("Page 3", "Section 2"). */
        val label: String,
        val sourceTitle: String,
        val writingCardId: Long,
        val writingTitle: String,
    )

    private fun excerptsOf(json: String): List<WritingExcerpt> {
        val rows = JSONArray(json)
        return List(rows.length()) {
            val row = rows.getJSONObject(it)
            WritingExcerpt(
                anchor = row.getString("anchor"),
                kind = row.getString("kind"),
                cardId = row.getLong("card_id"),
                page = row.optInt("page"),
                section = row.optInt("section"),
                offset = row.optInt("offset", -1),
                quote = row.getString("quote"),
                label = row.getString("label"),
                sourceTitle = row.getString("source_title"),
                writingCardId = row.optLong("writing_card_id"),
                writingTitle = row.optString("writing_title"),
            )
        }
    }

    /** Quotes a passage of a PDF or EPUB ([source]: kind, card_id, quote and its place) into a writing topic; its title. */
    @WorkerThread
    fun sendToWriting(
        source: JSONObject,
        writingCardId: Long,
    ): String = JSONObject(requireFacade().callAttr("send_to_writing", source.toString(), writingCardId).toString()).getString("title")

    /** The passages still quoted in a writing topic, each with where it is from. */
    @WorkerThread
    fun writingExcerpts(writingCardId: Long): List<WritingExcerpt> =
        excerptsOf(requireFacade().callAttr("writing_excerpts", writingCardId).toString())

    /** The writing topics that quote a page (PDF, 1-based) or chapter (EPUB, 0-based) of a document. */
    @WorkerThread
    fun sourceExcerpts(
        cardId: Long,
        kind: String,
        position: Int,
    ): List<WritingExcerpt> = excerptsOf(requireFacade().callAttr("source_excerpts", cardId, kind, position).toString())

    /** The writing topics in the library, to choose where a passage goes. */
    @WorkerThread
    fun writingTopics(): List<Pair<Long, String>> = library().filter { it.kind == "writing" }.map { it.cardId to it.title }

    /** Add a web page as an Incremento Web topic card; returns the card id. */
    @WorkerThread
    fun importWeb(
        url: String,
        title: String,
    ): Long = requireFacade().callAttr("import_web", url, title).toLong()

    /** The page to open for web topic [cardId] and a nonce for its bridge messages. */
    @WorkerThread
    fun webOpen(cardId: Long): Pair<String, String> {
        val json = JSONObject(requireFacade().callAttr("web_open", cardId).toString())
        return json.getString("url") to json.getString("nonce")
    }

    /** Desktop's web bridge script, with the saved position restore, for [currentUrl]. */
    @WorkerThread
    fun webScript(
        cardId: Long,
        nonce: String,
        currentUrl: String,
    ): String = requireFacade().callAttr("web_script", cardId, nonce, currentUrl).toString()

    @WorkerThread
    fun webMessage(
        cardId: Long,
        message: String,
    ) {
        requireFacade().callAttr("web_message", cardId, message)
    }

    /** Start an extract from selected page text; false when nothing is selected. */
    @WorkerThread
    fun webExtract(
        cardId: Long,
        text: String,
        url: String = "",
    ): Boolean = requireFacade().callAttr("web_extract", cardId, text, url).toBoolean()

    /** The managed PDF file of [cardId], or "" when the file is missing. */
    @WorkerThread
    fun pdfPath(cardId: Long): String = requireFacade().callAttr("pdf_path", cardId).toString()

    /** JavaScript that opens [cardId]'s PDF in upstream's viewer at its saved position. */
    @WorkerThread
    fun pdfStartScript(
        cardId: Long,
        fileUrl: String,
        workerUrl: String,
    ): String = requireFacade().callAttr("pdf_start_script", cardId, fileUrl, workerUrl).toString()

    /** Apply a `pycmd` message from the PDF viewer; returns JavaScript to run (may be empty). */
    @WorkerThread
    fun pdfMessage(
        cardId: Long,
        message: String,
    ): String = requireFacade().callAttr("pdf_message", cardId, message).toString()

    /** The pending extract from [sourceCardId]'s document, with Incremento's defaults. */
    @WorkerThread
    fun extractDraft(sourceCardId: Long): ExtractDraft {
        val draft = JSONObject(requireFacade().callAttr("extract_draft", sourceCardId).toString())
        val fields = draft.getJSONArray("fields")
        val noteTypes = draft.getJSONArray("note_types")
        return ExtractDraft(
            fields = List(fields.length()) { fields.getString(it) },
            noteTypes = List(noteTypes.length()) { noteTypes.getString(it) },
            fieldNames = fieldNames(draft),
            noteType = draft.getString("note_type"),
            isTopic = draft.getString("kind") == "topic",
            priority = draft.getDouble("priority"),
            noteTypeConfigured = draft.optBoolean("note_type_configured"),
        )
    }

    private fun fieldNames(draft: JSONObject): Map<String, List<String>> {
        val names = draft.optJSONObject("field_names") ?: return emptyMap()
        return names.keys().asSequence().associateWith { type ->
            val fields = names.getJSONArray(type)
            List(fields.length()) { fields.getString(it) }
        }
    }

    /** A card from scratch: upstream's note type and deck, the item/topic choice, priority 50. */
    @WorkerThread
    fun newCardDefaults(): ExtractDraft {
        val draft = JSONObject(requireFacade().callAttr("new_card_defaults").toString())
        val fields = draft.getJSONArray("fields")
        val noteTypes = draft.getJSONArray("note_types")
        val decks = draft.getJSONArray("decks")
        return ExtractDraft(
            fields = List(fields.length()) { fields.getString(it) },
            noteTypes = List(noteTypes.length()) { noteTypes.getString(it) },
            fieldNames = fieldNames(draft),
            noteType = draft.getString("note_type"),
            isTopic = draft.getString("kind") == "topic",
            priority = draft.getDouble("priority"),
            decks = List(decks.length()) { decks.getString(it) },
            deck = draft.getString("deck"),
        )
    }

    /** Adds a card from scratch with [priority] in [deck]; returns the note id. */
    @WorkerThread
    fun createCard(
        fields: List<String>,
        noteType: String,
        isTopic: Boolean,
        priority: Double,
        deck: String,
    ): Long =
        requireFacade()
            .callAttr("create_card", JSONArray(fields).toString(), noteType, if (isTopic) "topic" else "item", priority, deck)
            .toLong()

    /**
     * Add the extract as a note linked to its source document, as desktop does
     * (priority, topic/item tags, knowledge tree, PDF source). Returns
     * JavaScript refreshing the reader's cards for the page.
     */
    @WorkerThread
    fun createExtract(
        sourceCardId: Long,
        fields: List<String>,
        noteType: String,
        isTopic: Boolean,
        priority: Double,
    ): String {
        val kind = if (isTopic) "topic" else "item"
        val result =
            requireFacade().callAttr("create_extract", sourceCardId, JSONArray(fields).toString(), noteType, kind, priority)
        return JSONObject(result.toString()).getString("script")
    }

    @WorkerThread
    fun discardExtract(sourceCardId: Long) {
        requireFacade().callAttr("discard_extract", sourceCardId)
    }

    /** An extract set aside with "Add later": its front and the choices made for it. */
    data class QueuedExtract(
        val front: String,
        val isTopic: Boolean,
        val priority: Double,
    )

    /** Sets the extract aside, as chosen, and frees the draft; returns how many are waiting. */
    @WorkerThread
    fun queueExtract(
        sourceCardId: Long,
        fields: List<String>,
        noteType: String,
        isTopic: Boolean,
        priority: Double,
    ): Int =
        requireFacade()
            .callAttr("queue_extract", sourceCardId, JSONArray(fields).toString(), noteType, if (isTopic) "topic" else "item", priority)
            .toInt()

    @WorkerThread
    fun extractQueue(sourceCardId: Long): List<QueuedExtract> {
        val queued = JSONArray(requireFacade().callAttr("extract_queue", sourceCardId).toString())
        return List(queued.length()) { index ->
            val q = queued.getJSONObject(index)
            val front = q.getJSONArray("fields").optString(0)
            QueuedExtract(
                android.text.Html
                    .fromHtml(front, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .trim(),
                q.optString("kind") == "topic",
                q.optDouble("priority"),
            )
        }
    }

    @WorkerThread
    fun dropQueuedExtract(
        sourceCardId: Long,
        index: Int,
    ) {
        requireFacade().callAttr("drop_queued_extract", sourceCardId, index)
    }

    /** Adds the waiting extracts: how many, and the script that marks them on the page. */
    @WorkerThread
    fun addQueuedExtracts(sourceCardId: Long): Pair<Int, String> {
        val result = JSONObject(requireFacade().callAttr("add_queued_extracts", sourceCardId).toString())
        return result.getInt("added") to result.optString("script")
    }

    /** Incremento's statistics over the last [days] (see `facade.statistics`), as JSON. */
    @WorkerThread
    fun statistics(days: Int = 7): JSONObject = JSONObject(requireFacade().callAttr("statistics", days).toString())

    /** A link of a PDF page to a page of another (or the same) PDF; [direction] is "outgoing" or "incoming". */
    data class PageLink(
        val id: Long,
        val direction: String,
        val page: Int,
        val title: String,
        val available: Boolean,
    )

    /** A place in a PDF: its card, page and how far down the page. */
    data class PdfPlace(
        val cardId: Long,
        val page: Int,
        val scroll: Double,
    ) {
        companion object {
            fun from(json: JSONObject) = PdfPlace(json.getLong("card_id"), json.getInt("page"), json.optDouble("scroll", 0.0))
        }
    }

    /** [page]'s links from either end, as desktop's page links show them. */
    @WorkerThread
    fun pdfPageLinks(
        cardId: Long,
        page: Int,
    ): List<PageLink> {
        val rows = JSONArray(requireFacade().callAttr("pdf_page_links", cardId, page).toString())
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            PageLink(
                row.getLong("id"),
                row.getString("direction"),
                row.getInt("target_page"),
                row.getString("title"),
                row.getBoolean("available"),
            )
        }
    }

    /** Where [link] opens; throws when its document is gone or the page is past its daily limit. */
    @WorkerThread
    fun pdfPageLinkTarget(
        cardId: Long,
        page: Int,
        link: PageLink,
    ): PdfPlace =
        PdfPlace.from(JSONObject(requireFacade().callAttr("pdf_page_link_target", cardId, page, link.id, link.direction).toString()))

    @WorkerThread
    fun pdfPageLinkRemove(
        cardId: Long,
        page: Int,
        link: PageLink,
    ): Boolean = requireFacade().callAttr("pdf_page_link_remove", cardId, page, link.id).toBoolean()

    /** Links [sourcePage] of [sourceCardId] to [target]; throws as desktop refuses (a page past the daily limit). */
    @WorkerThread
    fun pdfPageLinkAdd(
        sourceCardId: Long,
        sourcePage: Int,
        sourceY: Double?,
        target: PdfPlace,
    ): Long =
        requireFacade()
            .callAttr("pdf_page_link_add", sourceCardId, sourcePage, sourceY, target.cardId, target.page, target.scroll, null)
            .toLong()

    /** A pasted Q/A pair as desktop's batch parsing reads it; [error] says why an invalid one can't become a card. */
    data class BatchRow(
        val question: String,
        val answer: String,
        val valid: Boolean,
        val error: String,
    )

    @WorkerThread
    fun extractBatchPreview(text: String): List<BatchRow> {
        val rows = JSONArray(requireFacade().callAttr("extract_batch_preview", text).toString())
        return List(rows.length()) { index ->
            rows.getJSONObject(index).let {
                BatchRow(it.optString("question"), it.optString("answer"), it.optBoolean("valid"), it.optString("error"))
            }
        }
    }

    /** Desktop's names for the batch sheet: button, title, hint, create, question, answer, invalid. */
    @WorkerThread
    fun extractBatchLabels(): Map<String, String> {
        val labels = JSONObject(requireFacade().callAttr("extract_batch_labels").toString())
        return labels.keys().asSequence().associateWith { labels.getString(it) }
    }

    /** One card per valid pair: under [sourceCardId], or from scratch in [deck] when it is 0. Returns how many and desktop's summary. */
    @WorkerThread
    fun extractBatchCreate(
        sourceCardId: Long,
        text: String,
        noteType: String,
        isTopic: Boolean,
        priority: Double,
        deck: String,
    ): Pair<Int, String> {
        val result =
            JSONObject(
                requireFacade()
                    .callAttr("extract_batch_create", sourceCardId, text, noteType, if (isTopic) "topic" else "item", priority, deck)
                    .toString(),
            )
        return result.getInt("created") to result.getString("summary")
    }

    /** Desktop's support bundle (redacted settings, recent diagnostics) written to [file]. */
    @WorkerThread
    fun supportBundle(file: File) {
        requireFacade().callAttr("support_bundle", file.absolutePath)
    }

    /** Incremento note types older than this version's, each with what would change. */
    @WorkerThread
    fun cardFormatUpdates(): List<Pair<String, List<String>>> {
        val rows = JSONArray(requireFacade().callAttr("card_format_updates").toString())
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val changes = row.getJSONArray("changes")
            row.getString("note_type") to List(changes.length()) { changes.getString(it) }
        }
    }

    /** Applies the pending card format updates; returns the note types changed. */
    @WorkerThread
    fun applyCardFormatUpdates(): List<String> {
        val names = JSONArray(requireFacade().callAttr("apply_card_format_updates").toString())
        return List(names.length()) { names.getString(it) }
    }

    /** Desktop's statistics dialog for one of its scopes ("daily", "7d", "30d", "365d", "lifetime"). */
    @WorkerThread
    fun statisticsView(scope: String): JSONObject = JSONObject(requireFacade().callAttr("statistics_view", scope).toString())

    /** One entry of the knowledge tree, in display order with its [depth]. */
    data class TreeNode(
        val cardId: Long,
        val depth: Int,
        val title: String,
        val kind: String,
        val priority: Double?,
        val parentId: Long? = null,
    )

    /** A node matching a tree search, with upstream's reason ("Title", a field's name). */
    data class TreeHit(
        val cardId: Long,
        val title: String,
        val kind: String,
        val reason: String,
    )

    /** Upstream's simulation of postponing a branch: the preset used ("" for the defaults), its summary, how many move. */
    data class PostponePlan(
        val preset: String,
        val lines: List<String>,
        val count: Int,
    )

    @WorkerThread
    fun treeSearch(query: String): List<TreeHit> {
        val hits = JSONArray(requireFacade().callAttr("tree_search", query).toString())
        return List(hits.length()) { index ->
            val hit = hits.getJSONObject(index)
            TreeHit(hit.getLong("card_id"), displayTitle(hit.optString("title")), hit.optString("node_kind"), hit.optString("match_reason"))
        }
    }

    @WorkerThread
    fun treePostponePlan(rootCardId: Long): PostponePlan {
        val plan = JSONObject(requireFacade().callAttr("tree_postpone_plan", rootCardId).toString())
        val lines = plan.getJSONArray("lines")
        return PostponePlan(plan.optString("preset"), List(lines.length()) { lines.getString(it) }, plan.optInt("count"))
    }

    /** Postpones the branch as planned; desktop's message about it. */
    @WorkerThread
    fun treePostponeBranch(rootCardId: Long): String =
        JSONObject(requireFacade().callAttr("tree_postpone_branch", rootCardId).toString()).optString("message")

    @WorkerThread
    fun knowledgeTree(): List<TreeNode> {
        val rows = JSONArray(requireFacade().callAttr("knowledge_tree").toString())
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            TreeNode(
                cardId = row.getLong("card_id"),
                depth = row.getInt("depth"),
                title = displayTitle(row.optString("title")),
                kind = row.optString("node_kind"),
                priority = if (row.isNull("priority")) null else row.getDouble("priority"),
                parentId = if (row.isNull("parent_card_id")) null else row.getLong("parent_card_id"),
            )
        }
    }

    /** A card the next session would get: its title, and upstream's type, pick mode and tag labels. */
    data class PreviewEntry(
        val cardId: Long,
        val title: String,
        val type: String,
        val mode: String,
        val tag: String,
    )

    /** The next session's cards, as desktop's dialog previews them, and how many the session asks for. */
    data class SessionPreview(
        val target: Int,
        val entries: List<PreviewEntry>,
    )

    /** Upstream's selection for the next session; starting then uses these cards while the settings stay the same. */
    @WorkerThread
    fun sessionPreview(): SessionPreview {
        val preview = JSONObject(requireFacade().callAttr("session_preview").toString())
        val entries = preview.getJSONArray("entries")
        return SessionPreview(
            preview.optInt("target"),
            List(entries.length()) { index ->
                val e = entries.getJSONObject(index)
                PreviewEntry(
                    e.getLong("card_id"),
                    displayTitle(e.optString("title")),
                    e.optString("type"),
                    e.optString("mode"),
                    e.optString("tag"),
                )
            },
        )
    }

    /** How many of a document's cards a review takes, and desktop's names for each choice (group to value, label). */
    data class DocumentReviewPreview(
        val selected: Int,
        val topics: Int,
        val items: Int,
        val labels: Map<String, List<Pair<String, String>>>,
    )

    @WorkerThread
    fun documentReviewPreview(
        cardId: Long,
        kind: String,
        position: Double,
        choices: Map<String, String>,
    ): DocumentReviewPreview {
        val preview =
            JSONObject(
                requireFacade().callAttr("document_review_preview", cardId, kind, position, JSONObject(choices).toString()).toString(),
            )
        val labels = preview.getJSONObject("labels")
        return DocumentReviewPreview(
            preview.getInt("selected"),
            preview.getInt("topics"),
            preview.getInt("items"),
            labels.keys().asSequence().associateWith { group ->
                val options = labels.getJSONArray(group)
                List(options.length()) { options.getJSONArray(it).let { pair -> pair.getString(0) to pair.getString(1) } }
            },
        )
    }

    /** Starts reviewing a document's cards; [Event.EnterReview] follows, or a message when none match. */
    @WorkerThread
    fun documentReviewStart(
        cardId: Long,
        kind: String,
        position: Double,
        choices: Map<String, String>,
    ): Boolean = requireFacade().callAttr("document_review_start", cardId, kind, position, JSONObject(choices).toString()).toBoolean()

    /** Start a session limited to [rootCardId]'s branch; [Event.EnterReview] follows. */
    @WorkerThread
    fun startBranchSession(rootCardId: Long): Boolean = requireFacade().callAttr("start_branch_session", rootCardId).toBoolean()

    /** Session settings (desktop's Basic view), as percentages. */
    data class SessionSettings(
        val cardCount: Int,
        val topicsPercent: Int,
        val documentsPercent: Int,
        val randomPercent: Int,
        val includeNew: Boolean,
        val includeLearning: Boolean,
        val includeDue: Boolean,
        val autoRefill: Boolean,
    )

    @WorkerThread
    fun sessionSettings(): SessionSettings {
        val json = JSONObject(requireFacade().callAttr("session_settings").toString())
        return SessionSettings(
            cardCount = json.getInt("card_count"),
            topicsPercent = json.getInt("topics_percent"),
            documentsPercent = json.getInt("documents_percent"),
            randomPercent = json.getInt("random_percent"),
            includeNew = json.getBoolean("include_new"),
            includeLearning = json.getBoolean("include_learning"),
            includeDue = json.getBoolean("include_due"),
            autoRefill = json.getBoolean("auto_refill"),
        )
    }

    @WorkerThread
    fun saveSessionSettings(settings: SessionSettings) {
        val json =
            JSONObject()
                .put("card_count", settings.cardCount)
                .put("topics_percent", settings.topicsPercent)
                .put("documents_percent", settings.documentsPercent)
                .put("random_percent", settings.randomPercent)
                .put("include_new", settings.includeNew)
                .put("include_learning", settings.includeLearning)
                .put("include_due", settings.includeDue)
                .put("auto_refill", settings.autoRefill)
        requireFacade().callAttr("save_session_settings", json.toString())
    }

    /** Pick session cards with the saved Incremento scheduler settings. */
    @WorkerThread
    fun selectSession(): List<Long> {
        val result = JSONObject(requireFacade().callAttr("select_session").toString())
        val ids = result.getJSONArray("card_ids")
        return List(ids.length()) { ids.getLong(it) }
    }

    /** Fill the "Incremento Session" filtered deck with [cardIds]; returns its deck id. */
    @WorkerThread
    fun buildSessionDeck(cardIds: List<Long>): Long {
        val result = requireFacade().callAttr("build_session_deck", JSONArray(cardIds).toString())
        return JSONObject(result.toString()).getLong("deck_id")
    }

    /**
     * Incremento's answer buttons for [cardId], or null when AnkiDroid's own
     * buttons apply (Ankrimento not started, or an ordinary card).
     */
    @WorkerThread
    fun answerButtons(cardId: Long): List<AnswerButton>? {
        if (!isStarted) return null
        val result = JSONObject(requireFacade().callAttr("answer_buttons", cardId).toString())
        if (result.getString("mode") == "standard") return null
        val buttons = result.getJSONArray("buttons")
        return List(buttons.length()) { index ->
            val button = buttons.getJSONObject(index)
            AnswerButton(
                rating = Rating.forNumber(button.getInt("slot") - 1),
                label = button.getString("label"),
                ease = button.getInt("ease"),
                due = if (button.isNull("due")) null else button.getString("due"),
            )
        }
    }

    /**
     * The ease (1-4) Anki should record when [rating]'s button is pressed.
     * Topic choices and Pass are recorded as Good.
     */
    @WorkerThread
    fun beforeAnswer(
        cardId: Long,
        rating: Rating,
    ): Int {
        val result = JSONObject(requireFacade().callAttr("before_answer", cardId, rating.number + 1).toString())
        return result.getInt("ease")
    }

    /** Apply Incremento's post-answer scheduling after AnkiDroid answered [cardId]. */
    @WorkerThread
    fun afterAnswer(
        cardId: Long,
        ease: Int,
    ) {
        requireFacade().callAttr("after_answer", cardId, ease)
    }

    /**
     * Reviewer hook, before answering: the [Rating] AnkiDroid should submit.
     * Unchanged until an Incremento session has started Ankrimento.
     */
    @WorkerThread
    fun ratingToSubmit(
        cardId: Long,
        rating: Rating,
    ): Rating {
        if (!isStarted) return rating
        return Rating.forNumber(beforeAnswer(cardId, rating) - 1)
    }

    /** Reviewer hook, after AnkiDroid's answer op created its undo step. */
    @WorkerThread
    fun onCardAnswered(
        cardId: Long,
        rating: Rating,
    ) {
        if (isStarted) afterAnswer(cardId, rating.number + 1)
    }

    private fun requireFacade(): PyObject = checkNotNull(facade) { "Ankrimento not started" }

    private fun onHostEvent(
        event: String,
        fieldsJson: String,
    ) {
        val fields = JSONObject(fieldsJson)
        when (event) {
            "operation_did_execute" -> {
                val bytes = Base64.decode(fields.getString("changes"), Base64.DEFAULT)
                ChangeManager.publish(OpChanges.parseFrom(bytes))
            }
            "message" -> mutableEvents.tryEmit(Event.Message(fields.getString("kind"), fields.getString("text")))
            "move_to_state" -> if (fields.getString("state") == "review") mutableEvents.tryEmit(Event.EnterReview)
            "pdf_close" -> mutableEvents.tryEmit(Event.CloseReader(fields.getLong("card_id")))
            "open_url" -> mutableEvents.tryEmit(Event.OpenUrl(fields.getString("url")))
            "extract_draft" -> mutableEvents.tryEmit(Event.ExtractDraft(fields.getLong("card_id")))
            "pdf_open" -> mutableEvents.tryEmit(Event.PdfOpen(fields.getLong("source_card_id"), PdfPlace.from(fields)))
            "pdf_page_link_create", "pdf_page_links_show" ->
                mutableEvents.tryEmit(
                    Event.PdfPageLinks(
                        fields.getLong("card_id"),
                        fields.getInt("page"),
                        create = event == "pdf_page_link_create",
                        pageY = fields.optDouble("page_y").takeIf { !it.isNaN() },
                    ),
                )
            else -> Timber.d("Ankrimento event %s %s", event, fieldsJson)
        }
    }

    /** An extract waiting to become a card; [fields] are HTML. */
    data class ExtractDraft(
        val fields: List<String>,
        val noteTypes: List<String>,
        val noteType: String,
        val isTopic: Boolean,
        val priority: Double,
        /** Desktop's extract note type setting chose [noteType] (otherwise the phone may choose it, see [ExtractNoteTypes]). */
        val noteTypeConfigured: Boolean = false,
        /** For a card from scratch: the decks to choose from, and the one chosen first. */
        val decks: List<String> = emptyList(),
        val deck: String = "",
        /** Each note type's first two field names: the labels of the fields the sheet edits. */
        val fieldNames: Map<String, List<String>> = emptyMap(),
    )

    /**
     * One of Incremento's answer buttons, placed on AnkiDroid's [rating] button.
     *
     * @param ease what Anki records; its next-interval text applies when [due] is null
     * @param due the next interval Incremento computed (topics)
     */
    data class AnswerButton(
        val rating: Rating,
        val label: String,
        val ease: Int,
        val due: String?,
    )
}
