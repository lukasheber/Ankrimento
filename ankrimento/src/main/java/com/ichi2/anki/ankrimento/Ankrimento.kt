// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.annotation.WorkerThread
import anki.collection.OpChanges
import anki.scheduler.CardAnswer.Rating
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Incremento's backend, running in embedded Python on AnkiDroid's collection.
 *
 * Call from the collection queue (`withCol`): Python uses the same backend and
 * undo queue as AnkiDroid, so its work must be serialized with AnkiDroid's.
 */
object Ankrimento {
    /** Desktop's default profile; selects `user_files/<profile>/` as on desktop. */
    const val DEFAULT_PROFILE = "User 1"

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
    }

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = mutableEvents

    private var facade: PyObject? = null

    val isSupported: Boolean
        get() = Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    val isStarted: Boolean
        get() = facade != null

    private var syncStore: SafStore? = null
    private var deviceName = ""

    /**
     * Starts Python and imports the slow, collection-independent modules, off
     * the collection queue, so a later [start] is quick. Only once Incremento
     * has been used on this device.
     */
    @WorkerThread
    fun warmUp(context: Context) {
        if (!isSupported || facade != null || !AnkrimentoSettings(context).hasStarted) return
        val startedAt = System.currentTimeMillis()
        startPython(context)
        Python.getInstance().getModule("ankrimento.android").callAttr("warm_up")
        Timber.i("Ankrimento warmed up in %d ms", System.currentTimeMillis() - startedAt)
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
        deviceName = settings.deviceName
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
        )
        facade = python.getModule("ankrimento.facade")
        ChangeManager.subscribe(operationListener)
        settings.syncFolder?.let { syncStore = SafStore(context.applicationContext, it, profileStorageName()) }
        Timber.i("Ankrimento started for profile '%s'", profile)
    }

    /** The profile's folder name in user_files/ and in the sync folder. */
    private fun profileStorageName(): String = requireFacade().callAttr("profile_name").toString()

    /** What a sync did, or would do: see `ankrimento.syncfolder.Status`. */
    data class SyncResult(
        val state: String,
        val remoteDevice: String,
        val leaseHolder: String,
    )

    val isSyncConfigured: Boolean
        get() = syncStore != null

    /** Use [folder] (picked with ACTION_OPEN_DOCUMENT_TREE) to sync Incremento data. */
    fun setSyncFolder(
        context: Context,
        folder: Uri,
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(folder, flags)
        AnkrimentoSettings(context).syncFolder = folder
        if (isStarted) syncStore = SafStore(context.applicationContext, folder, profileStorageName())
    }

    /**
     * Sync with the sync folder; null when none is set. [action] is "status",
     * "sync", "keep_device", "keep_folder" or "release" (see `facade.sync`).
     */
    @WorkerThread
    fun sync(action: String = "sync"): SyncResult? {
        val store = syncStore ?: return null
        val result = Python.getInstance().getModule("ankrimento.android").callAttr("sync", store, deviceName, action)
        val json = JSONObject(result.toString())
        return SyncResult(json.getString("state"), json.getString("remote_device"), json.getString("lease_holder"))
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
     * Start a session with Incremento's saved scheduler settings, as desktop's
     * "Start Incremental Learning" does. Returns at once; [Event.EnterReview]
     * follows when the session deck is ready, or an [Event.Message].
     */
    fun startSession() {
        requireFacade().callAttr("start_session")
    }

    /** Reviewer hook: a card's question is on screen. Records whether it is a topic. */
    @WorkerThread
    fun questionShown(cardId: Long) {
        if (!isStarted) return
        requireFacade().callAttr("question_shown", cardId)
        shownTopicCardId = if (requireFacade().callAttr("is_topic", cardId).toBoolean()) cardId else null
    }

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
            runCatching { CollectionManager.withCol { sync("release") } }
                .onFailure { Timber.w(it, "Incremento sync on close failed") }
        }
    }

    /** The Incremento reader for [cardId]: "pdf", or "" when it has none. */
    @WorkerThread
    fun cardReader(cardId: Long): String = if (isStarted) requireFacade().callAttr("card_reader", cardId).toString() else ""

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
    ): String = requireFacade().callAttr("epub_script", cardId, section.index, section.nonce, scrollRatio ?: -1.0).toString()

    /** Apply a page-script message; returns the chapter to load next and its scroll ratio, if any. */
    @WorkerThread
    fun epubMessage(
        cardId: Long,
        sectionIndex: Int,
        message: String,
    ): Pair<Int, Double>? {
        val json = JSONObject(requireFacade().callAttr("epub_message", cardId, sectionIndex, message).toString())
        val navigate = json.optJSONObject("navigate") ?: return null
        return navigate.getInt("section_index") to navigate.getDouble("scroll_ratio")
    }

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
            noteType = draft.getString("note_type"),
            isTopic = draft.getString("kind") == "topic",
            priority = draft.getDouble("priority"),
        )
    }

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
