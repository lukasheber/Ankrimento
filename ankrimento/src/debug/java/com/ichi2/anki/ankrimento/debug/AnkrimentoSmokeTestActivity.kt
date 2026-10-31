// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import anki.scheduler.CardAnswer.Rating
import com.chaquo.python.Python
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.EpubReaderActivity
import com.ichi2.anki.ankrimento.PdfReaderActivity
import com.ichi2.anki.ankrimento.ReaderActivity
import com.ichi2.anki.ankrimento.VideoReaderActivity
import com.ichi2.anki.ankrimento.WebReaderActivity
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Debug builds only: runs Ankrimento's review flow on the device's real
 * collection and logs each step under the `AnkrimentoSmoke` tag.
 *
 * `adb shell am start -n com.ichi2.anki.debug/com.ichi2.anki.ankrimento.debug.AnkrimentoSmokeTestActivity`
 *
 * Adds a few notes tagged `ankrimento-smoke` to the "Topics" and "Default" decks.
 */
class AnkrimentoSmokeTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(output) })
        // `--el open <card id>` opens that card's reader; with `--ez only true`, imports and
        // `open` run without the session check (which needs cards due).
        if (intent.getBooleanExtra(EXTRA_ONLY, false)) {
            scope.launch {
                val opened =
                    withContext(Dispatchers.IO) {
                        CollectionManager.withCol {
                            Ankrimento.start(this@AnkrimentoSmokeTestActivity, this)
                            val pdf = intent.getStringExtra(EXTRA_PDF)?.let { Ankrimento.importPdf(it, File(it).nameWithoutExtension) }
                            val epub = intent.getStringExtra(EXTRA_EPUB)?.let { Ankrimento.importEpub(it, File(it).nameWithoutExtension) }
                            pdf?.let { log("imported PDF card $it") }
                            epub?.let { log("imported EPUB card $it") }
                            val cardId = intent.getLongExtra(EXTRA_OPEN, 0L).takeIf { it > 0 } ?: epub ?: pdf
                            cardId?.let { it to Ankrimento.cardReader(it) }
                        }
                    }
                opened?.let { (cardId, reader) ->
                    ReaderActivity.intent(this@AnkrimentoSmokeTestActivity, reader, cardId, session = false)?.let(::startActivity)
                }
            }
            return
        }
        scope.launch {
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        CollectionManager.withCol { runSmokeTest(this) }
                    }
                    // Upstream's learnFunction, as the deck list menu starts it.
                    Ankrimento.startSession()
                    val event = withTimeout(60_000) { Ankrimento.events.first() }
                    log("learnFunction session: $event")
                    check(event == Ankrimento.Event.EnterReview) { "session did not reach review" }

                    // Optional: `--es pdf <path readable by the app>` imports it and opens the reader.
                    intent.getStringExtra(EXTRA_PDF)?.let { path ->
                        val title = File(path).nameWithoutExtension
                        val cardId = withContext(Dispatchers.IO) { CollectionManager.withCol { Ankrimento.importPdf(path, title) } }
                        log("imported PDF card $cardId")
                        startActivity(PdfReaderActivity.intent(this@AnkrimentoSmokeTestActivity, cardId))
                    }
                    // Optional: `--el web <card id>` / `--el video <card id>` opens that reader.
                    intent.getLongExtra(EXTRA_WEB, 0L).takeIf { it > 0 }?.let { cardId ->
                        startActivity(WebReaderActivity.intent(this@AnkrimentoSmokeTestActivity, cardId))
                    }
                    intent.getLongExtra(EXTRA_VIDEO, 0L).takeIf { it > 0 }?.let { cardId ->
                        startActivity(VideoReaderActivity.intent(this@AnkrimentoSmokeTestActivity, cardId))
                    }
                    // Optional: `--el open <card id>` opens that card's reader, whatever its kind.
                    intent.getLongExtra(EXTRA_OPEN, 0L).takeIf { it > 0 }?.let { cardId ->
                        val reader = withContext(Dispatchers.IO) { CollectionManager.withCol { Ankrimento.cardReader(cardId) } }
                        log("card $cardId opens in the '$reader' reader")
                        ReaderActivity.intent(this@AnkrimentoSmokeTestActivity, reader, cardId, session = false)?.let(::startActivity)
                    }
                    // Optional: `--es epub <path>` likewise for the EPUB reader.
                    intent.getStringExtra(EXTRA_EPUB)?.let { path ->
                        val title = File(path).nameWithoutExtension
                        val cardId = withContext(Dispatchers.IO) { CollectionManager.withCol { Ankrimento.importEpub(path, title) } }
                        log("imported EPUB card $cardId")
                        startActivity(EpubReaderActivity.intent(this@AnkrimentoSmokeTestActivity, cardId))
                    }
                }
            result
                .onSuccess { log("PASSED") }
                .onFailure { error ->
                    Log.e(TAG, "FAILED", error)
                    log("FAILED: ${Log.getStackTraceString(error)}")
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runSmokeTest(col: Collection) {
        val topics = ensureNotes(col, "Topics", "topic")
        ensureNotes(col, "Default", "item")
        log("notes ready: ${topics.size} topic cards")

        val startedAt = System.currentTimeMillis()
        Ankrimento.start(this, col)
        log("python started in ${System.currentTimeMillis() - startedAt} ms")

        val selected = Ankrimento.selectSession()
        log("selected ${selected.size} cards")
        check(selected.isNotEmpty()) { "no cards selected" }
        val deckId = Ankrimento.buildSessionDeck(selected)
        val deckCards = col.findCards("did:$deckId")
        log("session deck $deckId holds ${deckCards.size} cards")
        // Buried cards (e.g. postponed topics) are selected but cannot enter a filtered deck.
        check(deckCards.isNotEmpty() && selected.containsAll(deckCards)) { "deck holds cards that were not selected" }

        val topicCardId = deckCards.first { it in topics }
        val before = col.getCard(topicCardId)
        val rating = Ankrimento.ratingToSubmit(topicCardId, Rating.AGAIN) // button 1 = More
        log("topic button 1 -> submit $rating")
        check(rating == Rating.GOOD) { "topic answer should be submitted as Good" }

        col.sched.answerCard(col.getCard(topicCardId), rating)
        Ankrimento.onCardAnswered(topicCardId, rating)
        val facade = Python.getInstance().getModule("ankrimento.facade")
        val state = facade.callAttr("topic_state", topicCardId).toString()
        val answered = col.getCard(topicCardId)
        log("topic state $state; card ivl=${answered.ivl} oDid=${answered.oDid}")
        check(state.contains("\"a_factor\":3.15")) { "A-factor should be 3.5 x 0.9" }
        check(answered.oDid == 0L) { "answered card should return to its home deck" }

        col.undo()
        val restored = col.getCard(topicCardId)
        log("after one undo: ivl=${restored.ivl} queue=${restored.queue} did=${restored.did}")
        check(restored.ivl == before.ivl && restored.queue == before.queue && restored.did == before.did) {
            "one undo should restore the answer and the A-factor interval together"
        }
        // col.undo() bypasses ChangeManager, so reconcile as Ankrimento does after any op.
        facade.callAttr("undo_state_changed")
        val stateAfterUndo = facade.callAttr("topic_state", topicCardId).toString()
        log("topic state after undo: $stateAfterUndo")
        check(stateAfterUndo == "null") { "undo should remove the topic's A-factor state" }
    }

    private fun ensureNotes(
        col: Collection,
        deckName: String,
        prefix: String,
    ): Set<Long> {
        val query = "\"deck:$deckName\" tag:$SMOKE_TAG"
        val existing = col.findCards(query)
        if (existing.isNotEmpty()) return existing.toSet()
        val deckId = col.decks.id(deckName)
        val notetype = checkNotNull(col.notetypes.byName("Basic")) { "Basic note type missing" }
        repeat(3) { index ->
            val note = col.newNote(notetype)
            note.setField(0, "$prefix $index")
            note.setField(1, "back")
            note.addTag(SMOKE_TAG)
            col.addNote(note, deckId)
        }
        return col.findCards(query).toSet()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread { output.append("$message\n") }
    }

    private companion object {
        const val TAG = "AnkrimentoSmoke"
        const val SMOKE_TAG = "ankrimento-smoke"
        const val EXTRA_PDF = "pdf"
        const val EXTRA_EPUB = "epub"
        const val EXTRA_WEB = "web"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_OPEN = "open"
        const val EXTRA_ONLY = "only"
    }
}
