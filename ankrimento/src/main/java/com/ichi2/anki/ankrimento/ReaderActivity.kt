// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.BatchSheet
import com.ichi2.anki.ankrimento.ui.DocumentReviewSheet
import com.ichi2.anki.ankrimento.ui.ExtractChoice
import com.ichi2.anki.ankrimento.ui.ExtractQueueChip
import com.ichi2.anki.ankrimento.ui.ExtractQueueSheet
import com.ichi2.anki.ankrimento.ui.ExtractSheet
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import com.ichi2.anki.ankrimento.ui.PriorityDialog
import com.ichi2.anki.ankrimento.ui.ReaderLoading
import com.ichi2.anki.ankrimento.ui.ReaderPager
import com.ichi2.anki.ankrimento.ui.ReaderProgress
import com.ichi2.anki.ankrimento.ui.ReaderScaffold
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.ReviewBarState
import com.ichi2.anki.ankrimento.ui.RevisitDateDialog
import com.ichi2.anki.ankrimento.ui.TimerChip
import com.ichi2.anki.ankrimento.ui.TimerSheet
import com.ichi2.anki.ankrimento.ui.TimerSummaryDialog
import com.ichi2.anki.common.destinations.DeferredNavigation
import com.ichi2.anki.common.destinations.ReviewDeckDestination
import com.ichi2.anki.common.destinations.toIntent
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The screen every Incremento reader shares: the document under a top bar
 * with the reader's tools and, in a session, the review bar.
 *
 * In a session the reader *is* the review of a topic, as in SuperMemo: the
 * answer (or a topic action: Done, Postpone, Revisit) is recorded here and
 * the next element follows at once, a topic in its reader and anything else
 * in the reviewer, which receives no rating then ([LearnLoop]). The review
 * bar's Back returns to the previous element. The system Back leaves the
 * session ([RESULT_LEAVE_SESSION]); "Show card" returns to the card in the
 * reviewer. A reader that closes by itself (a missing file) returns to the card.
 *
 * Opened from the library, a reader is for reading only and has no review bar.
 */
abstract class ReaderActivity : ComponentActivity() {
    protected val cardId by lazy { intent.getLongExtra(EXTRA_CARD_ID, 0L) }
    protected val inSession by lazy { intent.getBooleanExtra(EXTRA_SESSION, false) }
    private var canGoBack by mutableStateOf(false)

    /** True while an answer or Back is on its way, so a second tap does not answer the next card too. */
    private var moving = false

    /** Where in the document the reader is, under the title ("Page 4"). */
    protected var subtitle by mutableStateOf<String?>(null)

    /** The reader's own tools, most used first. */
    protected var tools by mutableStateOf<List<ReaderTool>>(emptyList())

    /** How much of the document is read, shown under it. */
    protected var progress by mutableStateOf<ReaderProgress?>(null)

    /** Page turning under a paged document. */
    protected var pager by mutableStateOf<ReaderPager?>(null)

    /** A sheet the reader shows over the document (reading settings, contents), or null. */
    protected var sheet by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** Drawn over the reader's page, such as find's previous/next bar. */
    protected var overlay by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)

    /** While set, the review bar offers Show answer, which runs it, instead of the answers (items). */
    protected var revealAction by mutableStateOf<(() -> Unit)?>(null)

    /** The answer was shown before the answer buttons (items); topics count it when answered. */
    protected open val answerAlreadyShown = false

    /** The answer buttons for this card, read on the collection's thread. */
    protected open fun answerButtonsFor(col: Collection): List<Ankrimento.AnswerButton>? = Ankrimento.answerButtons(cardId)

    /** False while only the document shows (immersive reading). */
    private var chromeVisible by mutableStateOf(true)

    private var content by mutableStateOf<View?>(null)
    private var title by mutableStateOf("")
    private var answerButtons by mutableStateOf<List<Ankrimento.AnswerButton>>(emptyList())
    private var editedPriority by mutableStateOf<Double?>(null)
    private var choosingRevisitDate by mutableStateOf(false)
    private var priority by mutableStateOf<Double?>(null)
    private var timerSheet by mutableStateOf(false)

    /** A review of this document's cards was asked for: its deck's review opens when ready. */
    private var documentReviewStarting = false
    private var reviewChoices = emptyMap<String, String>()

    /** Extracts set aside with Add later, added together (or on leaving this document). */
    private var queuedExtracts by mutableStateOf(0)

    /** Desktop's review buttons shown: Done, Postpone (topic actions) and Extract (the extract tool). */
    private var reviewButtons by mutableStateOf(setOf("done", "postpone", "extract"))

    /** Today's pages read and cards reviewed, for the focus timer's summary. */
    private var today by mutableStateOf<Pair<Int, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IncrementoTheme {
                val timer by FocusTimer.state.collectAsState()
                val finishedRun by FocusTimer.finished.collectAsState()
                ReaderScaffold(
                    title = title,
                    subtitle = subtitle,
                    // Desktop's Extract review button hidden: so is the extract tool (the selection menu keeps it).
                    tools =
                        if ("extract" in
                            reviewButtons
                        ) {
                            tools
                        } else {
                            tools.filterNot { it.label == getString(R.string.ankrimento_extract_selection) }
                        },
                    menu = menu(),
                    review = reviewBar(),
                    onBack = ::leave,
                    chromeVisible = chromeVisible,
                    priority = priority,
                    onPriority = ::editPriority,
                    progress = progress,
                    pager = pager,
                    timer =
                        if (timer.active || queuedExtracts > 0) {
                            {
                                if (queuedExtracts > 0) ExtractQueueChip(queuedExtracts, ::showExtractQueue)
                                if (timer.active) TimerChip(timer) { timerSheet = true }
                            }
                        } else {
                            null
                        },
                ) { modifier ->
                    val view = content
                    if (view == null) {
                        ReaderLoading(modifier)
                    } else {
                        Box(modifier) {
                            key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
                            overlay?.invoke(this)
                        }
                    }
                }
                sheet?.invoke()
                if (timerSheet) {
                    TimerSheet(
                        timer,
                        onPick = FocusTimer::pick,
                        onStart = FocusTimer::start,
                        onPause = FocusTimer::pause,
                        onReset = FocusTimer::reset,
                        onDismiss = { timerSheet = false },
                    )
                }
                finishedRun?.let { run ->
                    LaunchedEffect(run) { today = todayCounts() }
                    TimerSummaryDialog(run, today?.first, today?.second) {
                        FocusTimer.finished.value = null
                        today = null
                    }
                }
                if (choosingRevisitDate) {
                    RevisitDateDialog(onDismiss = { choosingRevisitDate = false }) { date ->
                        choosingRevisitDate = false
                        topicAction { Ankrimento.topicRevisitOn(cardId, date) }
                    }
                }
                editedPriority?.let { current ->
                    PriorityDialog(current, onDismiss = { editedPriority = null }) { chosen ->
                        editedPriority = null
                        lifecycleScope.launch {
                            withCol { Ankrimento.setPriority(cardId, chosen) }
                            priority = chosen
                        }
                    }
                }
            }
        }
        RecentDocuments.record(this, cardId)
        // Readers add their own callbacks after this one, which run first.
        onBackPressedDispatcher.addCallback(this) { leave() }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    // The reviewer tells Incremento about the cards it shows; the loop's are shown here.
                    if (inSession && !intent.getBooleanExtra(EXTRA_QUESTION_SHOWN, false)) Ankrimento.questionShown(cardId)
                    priority = Ankrimento.priority(cardId)
                    val buttons = Ankrimento.reviewButtons()
                    reviewButtons =
                        if (buttons.optBoolean("group", true)) {
                            setOf("done", "postpone", "extract").filterTo(mutableSetOf()) { buttons.optBoolean(it, true) }
                        } else {
                            emptySet()
                        }
                    Ankrimento.cardTitle(cardId) to if (inSession) answerButtonsFor(this) else null
                }
            }.onSuccess { (cardTitle, buttons) ->
                title = cardTitle
                answerButtons = buttons.orEmpty()
            }.onFailure { Timber.w(it, "Loading the reader's card failed") }
            if (inSession) canGoBack = runCatching { LearnLoop.canGoBack() }.getOrDefault(false)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect(::onEvent)
            }
        }
    }

    /** Shows the reader's document; a reader may replace it (a video's player). */
    protected fun setReaderContent(view: View) {
        // AndroidView would otherwise wrap the content, and a WebView whose height
        // wraps its content lays pages out in a zero-height viewport (100vh = 0).
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content = view
    }

    /** A tap on the page hides the bars and the status bar, for reading alone; another brings them back. */
    internal fun toggleBarsOnTap(view: ReaderWebView) {
        view.onTap = { showChrome(!chromeVisible) }
    }

    private fun showChrome(show: Boolean) {
        chromeVisible = show
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (show) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Asks the page to report where reading stopped now (it does so shortly after scrolling). */
    protected open fun reportPosition() = Unit

    /** Before the reader closes: the page's last position, given time to arrive and be saved. */
    private suspend fun keepPosition() {
        reportPosition()
        delay(POSITION_REPORT_MS)
    }

    /** Runs when the extract sheet created a card from this document, with the script that marks the source. */
    protected open fun onExtractCreated(script: String) = Unit

    /** A document reader's own events (a PDF's page links). */
    protected open fun onDocumentEvent(event: Ankrimento.Event) = Unit

    private fun onEvent(event: Ankrimento.Event) {
        when (event) {
            is Ankrimento.Event.ExtractDraft -> if (event.sourceCardId == cardId) showExtractSheet()
            is Ankrimento.Event.CloseReader -> if (event.cardId == cardId) finish()
            is Ankrimento.Event.OpenUrl -> openUrl(event.url)
            is Ankrimento.Event.Message -> toast(event.text)
            is Ankrimento.Event.PdfOpen, is Ankrimento.Event.PdfPageLinks -> onDocumentEvent(event)
            Ankrimento.Event.EnterReview ->
                if (documentReviewStarting) {
                    documentReviewStarting = false
                    startActivity(with(DeferredNavigation) { ReviewDeckDestination.CurrentDeck.toIntent() })
                }
        }
    }

    /** The pending extract from this document, in the extract sheet. */
    private fun showExtractSheet() {
        lifecycleScope.launch {
            val (loaded, labels, mediaDir) =
                runCatching { withCol { Triple(Ankrimento.extractDraft(cardId), Ankrimento.extractBatchLabels(), media.dir) } }
                    .getOrElse { error ->
                        Timber.w(error, "Loading the extract failed")
                        return@launch
                    }
            val draft = ExtractNoteTypes.choose(this@ReaderActivity, readerKind, loaded)
            sheet = {
                ExtractSheet(
                    mediaDir = mediaDir,
                    batchLabel = labels["button"],
                    onBatch = { choice, text -> showBatchSheet(labels, choice, text) },
                    draft = draft,
                    onAdd = { choice ->
                        sheet = null
                        addExtract(choice)
                    },
                    onLater = { choice ->
                        sheet = null
                        ExtractNoteTypes.remember(this@ReaderActivity, readerKind, choice.noteType)
                        queueExtract(choice)
                    },
                    onKeep = {
                        sheet = null
                        toast(getString(R.string.ankrimento_extract_kept))
                    },
                    onDiscard = {
                        sheet = null
                        lifecycleScope.launch { withCol { Ankrimento.discardExtract(cardId) } }
                    },
                )
            }
        }
    }

    private fun queueExtract(choice: ExtractChoice) {
        lifecycleScope.launch {
            runCatching {
                withCol { Ankrimento.queueExtract(cardId, choice.fields, choice.noteType, choice.isTopic, choice.priority) }
            }.onSuccess { count ->
                queuedExtracts = count
                toast(resources.getQuantityString(R.plurals.ankrimento_extract_queued_toast, count, count))
            }.onFailure { error ->
                Timber.w(error, "Queueing an extract failed")
                toast(getString(R.string.ankrimento_extract_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun showExtractQueue() {
        lifecycleScope.launch {
            val queued = runCatching { withCol { Ankrimento.extractQueue(cardId) } }.getOrDefault(emptyList())
            queuedExtracts = queued.size
            sheet = {
                ExtractQueueSheet(
                    queued = queued,
                    onRemove = { index ->
                        lifecycleScope.launch {
                            withCol { Ankrimento.dropQueuedExtract(cardId, index) }
                            showExtractQueue()
                        }
                    },
                    onAddAll = {
                        sheet = null
                        lifecycleScope.launch { addQueuedExtracts() }
                    },
                    onDismiss = { sheet = null },
                )
            }
        }
    }

    /** Adds the waiting extracts, marking them on the page while it is still shown. */
    private suspend fun addQueuedExtracts(onPage: Boolean = true) {
        if (queuedExtracts == 0) return
        runCatching { withCol { Ankrimento.addQueuedExtracts(cardId) } }
            .onSuccess { (added, script) ->
                queuedExtracts = 0
                if (onPage) onExtractCreated(script)
                toast(resources.getQuantityString(R.plurals.ankrimento_extract_queue_added, added, added))
            }.onFailure { error ->
                Timber.w(error, "Adding queued extracts failed")
                queuedExtracts = runCatching { withCol { Ankrimento.extractQueue(cardId) }.size }.getOrDefault(0)
                toast(getString(R.string.ankrimento_extract_failed, error.message ?: error.javaClass.simpleName))
            }
    }

    /** Desktop's Batch Q/A from this document: each valid pair a card under it, with the sheet's choices. */
    private fun showBatchSheet(
        labels: Map<String, String>,
        choice: ExtractChoice,
        text: String,
    ) {
        sheet = {
            BatchSheet(
                labels = labels,
                initialText = text,
                preview = { withCol { Ankrimento.extractBatchPreview(it) } },
                onCreate = { pairs ->
                    sheet = null
                    ExtractNoteTypes.remember(this, readerKind, choice.noteType)
                    lifecycleScope.launch {
                        runCatching {
                            withCol {
                                Ankrimento.extractBatchCreate(
                                    cardId,
                                    pairs,
                                    choice.noteType,
                                    choice.isTopic,
                                    choice.priority,
                                    "",
                                )
                            }
                        }.onSuccess { (_, summary) -> toast(summary) }
                            .onFailure { error ->
                                Timber.w(error, "Adding batch extracts failed")
                                toast(getString(R.string.ankrimento_extract_failed, error.message ?: error.javaClass.simpleName))
                            }
                    }
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun addExtract(choice: ExtractChoice) {
        ExtractNoteTypes.remember(this, readerKind, choice.noteType)
        lifecycleScope.launch {
            runCatching {
                withCol { Ankrimento.createExtract(cardId, choice.fields, choice.noteType, choice.isTopic, choice.priority) }
            }.onSuccess { script ->
                onExtractCreated(script)
                // Where it went: under this document in the knowledge tree.
                toast(getString(R.string.ankrimento_extract_added_under, title))
            }.onFailure { error ->
                Timber.w(error, "Adding an extract failed")
                toast(getString(R.string.ankrimento_extract_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    /** This reader's kind ("pdf", "epub", …), as [intent] names it. */
    private val readerKind: String by lazy { READERS.entries.firstOrNull { it.value == javaClass }?.key ?: "text" }

    /** "pdf" or "epub" for readers whose cards desktop reviews together (Review All), with where reading is. */
    protected open val reviewKind: String? = null

    protected open fun reviewPosition(): Double = 0.0

    private fun showDocumentReview() {
        val kind = reviewKind ?: return
        val position = reviewPosition()
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.documentReviewPreview(cardId, kind, position, reviewChoices) } }
                .onSuccess { preview ->
                    sheet = {
                        DocumentReviewSheet(
                            preview = preview,
                            choices = reviewChoices,
                            onChoose = { group, value ->
                                reviewChoices = reviewChoices + (group to value)
                                showDocumentReview()
                            },
                            onReview = {
                                sheet = null
                                documentReviewStarting = true
                                lifecycleScope.launch {
                                    runCatching { withCol { Ankrimento.documentReviewStart(cardId, kind, position, reviewChoices) } }
                                        .onFailure { error ->
                                            documentReviewStarting = false
                                            toast(error.message ?: error.javaClass.simpleName)
                                        }
                                }
                            },
                            onDismiss = { sheet = null },
                        )
                    }
                }.onFailure { error ->
                    Timber.w(error, "Previewing the document's review failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    private fun menu(): List<ReaderTool> =
        buildList {
            if (inSession) {
                add(ReaderTool(getString(R.string.ankrimento_show_card)) { finishWith(RESULT_SHOW_CARD, null) })
            }
            add(ReaderTool(getString(R.string.ankrimento_timer_title)) { timerSheet = true })
            reviewKind?.let { add(ReaderTool(getString(R.string.ankrimento_document_review)) { showDocumentReview() }) }
            add(
                ReaderTool(
                    getString(R.string.ankrimento_tree_show),
                ) { startActivity(KnowledgeTreeActivity.intent(this@ReaderActivity, cardId)) },
            )
        }

    /** As desktop's summary reads today: pages read and cards reviewed, from Incremento's statistics. */
    private suspend fun todayCounts(): Pair<Int, Int>? =
        runCatching {
            val stats = withCol { Ankrimento.statistics(1) }
            val cards = stats.getJSONObject("today").getJSONObject("cards")
            val history = stats.getJSONArray("history")
            val pages = if (history.length() > 0) history.getJSONObject(history.length() - 1).optInt("pages") else 0
            pages to cards.keys().asSequence().sumOf { cards.optInt(it) }
        }.getOrNull()

    private fun reviewBar(): ReviewBarState? {
        if (!inSession || (answerButtons.isEmpty() && revealAction == null)) return null
        val topic = Ankrimento.shownTopicCardId == cardId
        val topicActions =
            if (topic) {
                listOfNotNull(
                    ReaderTool(getString(R.string.ankrimento_done), IncrementoIcons.Check) { topicAction { Ankrimento.topicDone(cardId) } }
                        .takeIf { "done" in reviewButtons },
                    ReaderTool(getString(R.string.ankrimento_postpone)) { topicAction { Ankrimento.topicPostpone(cardId) } }
                        .takeIf { "postpone" in reviewButtons },
                )
            } else {
                emptyList()
            }
        val revisitOptions =
            if (topic) {
                listOf(3, 6, 12).map { months ->
                    ReaderTool(resources.getQuantityString(R.plurals.ankrimento_months, months, months)) {
                        topicAction { Ankrimento.topicRevisit(cardId, months) }
                    }
                } + ReaderTool(getString(R.string.ankrimento_choose_date)) { choosingRevisitDate = true }
            } else {
                emptyList()
            }
        return ReviewBarState(
            answerButtons,
            topicActions,
            revisitOptions,
            onBack = if (canGoBack) ::goBack else null,
            reveal = revealAction,
        ) { rating ->
            continueAfter(rating = rating) {
                LearnLoop
                    .answer(this, cardId, rating, answerAlreadyShown) { suspended ->
                        toast(getString(if (suspended) R.string.ankrimento_leech_suspended else R.string.ankrimento_leech))
                    }.also { FocusTimer.cardAnswered() }
            }
        }
    }

    private fun editPriority() {
        lifecycleScope.launch { editedPriority = withCol { Ankrimento.priority(cardId) } }
    }

    /** Runs a topic action and, once it rescheduled the card, moves on to the next element. */
    private fun topicAction(action: () -> String) {
        continueAfter {
            LearnLoop.afterAction { toast(withCol { action() }) }
        }
    }

    private fun goBack() {
        continueAfter(back = true) {
            LearnLoop.back(this) ?: run {
                toast(getString(R.string.ankrimento_back_unavailable))
                null
            }
        }
    }

    /**
     * Runs [move] (an answer, a topic action, Back) and shows the element it
     * leads to: a topic in its reader, here, without passing through the
     * reviewer; anything else, or the end of the session, in the reviewer.
     */
    private fun continueAfter(
        back: Boolean = false,
        rating: Rating? = null,
        move: suspend () -> LearnLoop.Step?,
    ) {
        if (moving) return
        moving = true
        lifecycleScope.launch {
            val next =
                runCatching { move() }.getOrElse { error ->
                    Timber.w(error, "Incremento session step failed")
                    toast(error.message ?: error.javaClass.simpleName)
                    moving = false
                    return@launch
                }
            if (next == null) {
                moving = false
                return@launch
            }
            keepPosition()
            addQueuedExtracts(onPage = false)
            if (next == LearnLoop.NotCurrent) {
                finishWith(RESULT_OK, rating)
                return@launch
            }
            val following = (next as? LearnLoop.Next)?.let { intent(this@ReaderActivity, it.reader, it.cardId, session = true) }
            if (following != null) {
                // The reviewer waits for a result from this chain of readers.
                startActivity(following.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT))
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(if (back) android.R.anim.slide_in_left else android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                // Answered here: the reviewer redraws from the scheduler and shows what comes next.
                finishWith(RESULT_OK, null)
            }
        }
    }

    /** Back: in a session, ends it; the reviewer closes too. */
    private fun leave() {
        lifecycleScope.launch {
            keepPosition()
            addQueuedExtracts(onPage = false)
            if (inSession) finishWith(RESULT_LEAVE_SESSION, null) else finish()
        }
    }

    private fun finishWith(
        resultCode: Int,
        rating: Rating?,
    ) {
        val result = Intent().putExtra(EXTRA_CARD_ID, cardId)
        if (rating != null) result.putExtra(EXTRA_RATING, rating.number)
        setResult(resultCode, result)
        finish()
    }

    protected fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser for %s", url)
        }
    }

    protected fun toast(text: String) {
        if (text.isNotEmpty()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_CARD_ID = "card_id"

        /** Where to open: a PDF page (from 1) or an EPUB section (from 0). */
        const val EXTRA_START = "start"

        /** Words to find and highlight once open (PDF). */
        const val EXTRA_FIND = "find"
        private const val EXTRA_SESSION = "session"

        /** The reviewer already told Incremento this card's question is on screen. */
        const val EXTRA_QUESTION_SHOWN = "question_shown"

        /** The [Rating] number of the answer chosen in a session. */
        const val EXTRA_RATING = "rating"

        /** The reader closed to show its card in the reviewer. */
        const val RESULT_SHOW_CARD = RESULT_FIRST_USER

        /** Longer than upstream's pages wait after a scroll before reporting it (140 to 180 ms). */
        private const val POSITION_REPORT_MS = 450L

        /** The reader closed with Back: the session ends. */
        const val RESULT_LEAVE_SESSION = RESULT_FIRST_USER + 1

        /** Each kind of reader's screen. */
        private val READERS =
            mapOf(
                "pdf" to PdfReaderActivity::class.java,
                "epub" to EpubReaderActivity::class.java,
                "web" to WebReaderActivity::class.java,
                "video" to VideoReaderActivity::class.java,
                "writing" to WritingActivity::class.java,
                "text" to TextReaderActivity::class.java,
                "item" to ItemReaderActivity::class.java,
            )

        /**
         * The reader for [cardId], whose kind [Ankrimento.cardReader] returned,
         * or null when the card has none. In a [session] it reviews the card.
         */
        fun intent(
            context: Context,
            reader: String,
            cardId: Long,
            session: Boolean,
        ): Intent? {
            val activity = READERS[reader] ?: return null
            return Intent(context, activity).putExtra(EXTRA_CARD_ID, cardId).putExtra(EXTRA_SESSION, session)
        }

        /** A PDF at [position] (a page) or an EPUB at [position] (a section), outside a session, finding [query]. */
        fun intentAt(
            context: Context,
            reader: String,
            cardId: Long,
            position: Int,
            query: String,
        ): Intent? = intent(context, reader, cardId, session = false)?.putExtra(EXTRA_START, position)?.putExtra(EXTRA_FIND, query)
    }
}
