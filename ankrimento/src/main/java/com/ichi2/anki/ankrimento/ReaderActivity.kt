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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import com.ichi2.anki.ankrimento.ui.PriorityDialog
import com.ichi2.anki.ankrimento.ui.ReaderLoading
import com.ichi2.anki.ankrimento.ui.ReaderProgress
import com.ichi2.anki.ankrimento.ui.ReaderScaffold
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.ReviewBarState
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The screen every Incremento reader shares: the document under a top bar
 * with the reader's tools and, in a session, the review bar.
 *
 * In a session the reader *is* the review of a topic, as in SuperMemo: the
 * reviewer opens it for the card, and it ends with the answer, which it
 * returns ([EXTRA_RATING]) for the reviewer to record before it moves on.
 * A topic action (Done, Postpone, Revisit) reschedules the card and returns
 * without a rating; the reviewer then shows the next card. Back leaves the
 * session ([RESULT_LEAVE_SESSION]); "Show card" returns to the card in the
 * reviewer. A reader that closes by itself (a missing file) returns to the card.
 *
 * Opened from the library, a reader is for reading only and has no review bar.
 */
abstract class ReaderActivity : ComponentActivity() {
    protected val cardId by lazy { intent.getLongExtra(EXTRA_CARD_ID, 0L) }
    private val inSession by lazy { intent.getBooleanExtra(EXTRA_SESSION, false) }

    /** Where in the document the reader is, under the title ("Page 4"). */
    protected var subtitle by mutableStateOf<String?>(null)

    /** The reader's own tools, most used first. */
    protected var tools by mutableStateOf<List<ReaderTool>>(emptyList())

    /** How much of the document is read, shown under it. */
    protected var progress by mutableStateOf<ReaderProgress?>(null)

    /** A sheet the reader shows over the document (reading settings, contents), or null. */
    protected var sheet by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** False while only the document shows (immersive reading). */
    private var chromeVisible by mutableStateOf(true)

    private var content by mutableStateOf<View?>(null)
    private var title by mutableStateOf("")
    private var answerButtons by mutableStateOf<List<Ankrimento.AnswerButton>>(emptyList())
    private var editedPriority by mutableStateOf<Double?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IncrementoTheme {
                ReaderScaffold(
                    title = title,
                    subtitle = subtitle,
                    tools = tools,
                    menu = menu(),
                    review = reviewBar(),
                    onBack = ::leave,
                    chromeVisible = chromeVisible,
                    progress = progress,
                ) { modifier ->
                    val view = content
                    if (view == null) {
                        ReaderLoading(modifier)
                    } else {
                        key(view) { AndroidView(factory = { view }, modifier = modifier) }
                    }
                }
                sheet?.invoke()
                editedPriority?.let { current ->
                    PriorityDialog(current, onDismiss = { editedPriority = null }) { priority ->
                        editedPriority = null
                        lifecycleScope.launch { withCol { Ankrimento.setPriority(cardId, priority) } }
                    }
                }
            }
        }
        // Readers add their own callbacks after this one, which run first.
        onBackPressedDispatcher.addCallback(this) { leave() }
        lifecycleScope.launch {
            runCatching {
                withCol { Ankrimento.cardTitle(cardId) to if (inSession) Ankrimento.answerButtons(cardId) else null }
            }.onSuccess { (cardTitle, buttons) ->
                title = cardTitle
                answerButtons = buttons.orEmpty()
            }.onFailure { Timber.w(it, "Loading the reader's card failed") }
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

    /** Runs when the extract sheet created a card from this document, with the script that marks the source. */
    protected open fun onExtractCreated(script: String) = Unit

    private fun onEvent(event: Ankrimento.Event) {
        when (event) {
            is Ankrimento.Event.ExtractDraft -> if (event.sourceCardId == cardId) ExtractDialog.showFor(this, cardId, ::onExtractCreated)
            is Ankrimento.Event.CloseReader -> if (event.cardId == cardId) finish()
            is Ankrimento.Event.OpenUrl -> openUrl(event.url)
            is Ankrimento.Event.Message -> toast(event.text)
            Ankrimento.Event.EnterReview -> Unit
        }
    }

    private fun menu(): List<ReaderTool> =
        buildList {
            add(ReaderTool(getString(R.string.ankrimento_priority), onClick = ::editPriority))
            if (inSession) {
                add(ReaderTool(getString(R.string.ankrimento_show_card)) { finishWith(RESULT_SHOW_CARD, null) })
            }
        }

    private fun reviewBar(): ReviewBarState? {
        if (!inSession || answerButtons.isEmpty()) return null
        val topicActions =
            if (Ankrimento.shownTopicCardId == cardId) {
                listOf(
                    ReaderTool(
                        getString(R.string.ankrimento_topic_done),
                        IncrementoIcons.Check,
                    ) { topicAction { Ankrimento.topicDone(cardId) } },
                    ReaderTool(getString(R.string.ankrimento_topic_postpone)) { topicAction { Ankrimento.topicPostpone(cardId) } },
                    ReaderTool(getString(R.string.ankrimento_revisit_in, getString(R.string.ankrimento_topic_revisit_3))) {
                        topicAction { Ankrimento.topicRevisit(cardId, 3) }
                    },
                    ReaderTool(getString(R.string.ankrimento_revisit_in, getString(R.string.ankrimento_topic_revisit_6))) {
                        topicAction { Ankrimento.topicRevisit(cardId, 6) }
                    },
                    ReaderTool(getString(R.string.ankrimento_revisit_in, getString(R.string.ankrimento_topic_revisit_12))) {
                        topicAction { Ankrimento.topicRevisit(cardId, 12) }
                    },
                )
            } else {
                emptyList()
            }
        return ReviewBarState(answerButtons, topicActions) { rating -> finishWith(RESULT_OK, rating) }
    }

    private fun editPriority() {
        lifecycleScope.launch { editedPriority = withCol { Ankrimento.priority(cardId) } }
    }

    /** Runs a topic action and, once it rescheduled the card, returns to the reviewer for the next one. */
    private fun topicAction(action: () -> String) {
        lifecycleScope.launch {
            runCatching { withCol { action() } }
                .onSuccess { message ->
                    toast(message)
                    finishWith(RESULT_OK, null)
                }.onFailure { error ->
                    Timber.w(error, "Incremento topic action failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    /** Back: in a session, ends it; the reviewer closes too. */
    private fun leave() {
        if (inSession) finishWith(RESULT_LEAVE_SESSION, null) else finish()
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
        private const val EXTRA_SESSION = "session"

        /** The [Rating] number of the answer chosen in a session. */
        const val EXTRA_RATING = "rating"

        /** The reader closed to show its card in the reviewer. */
        const val RESULT_SHOW_CARD = RESULT_FIRST_USER

        /** The reader closed with Back: the session ends. */
        const val RESULT_LEAVE_SESSION = RESULT_FIRST_USER + 1

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
            val activity =
                when (reader) {
                    "pdf" -> PdfReaderActivity::class.java
                    "epub" -> EpubReaderActivity::class.java
                    "web" -> WebReaderActivity::class.java
                    "video" -> VideoReaderActivity::class.java
                    "writing" -> WritingActivity::class.java
                    else -> return null
                }
            return Intent(context, activity).putExtra(EXTRA_CARD_ID, cardId).putExtra(EXTRA_SESSION, session)
        }
    }
}
