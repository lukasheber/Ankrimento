// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.media.AudioManager
import android.media.ToneGenerator
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Desktop's focus timer (`frontend/timer_widget.py`, which imports Qt): a countdown from one
 * of its presets that counts, while it runs, the cards answered and the PDF and EPUB pages
 * read, then reports them with a tone. One timer for the app, so it keeps running from one
 * element to the next.
 */
object FocusTimer {
    /** Desktop's presets, in minutes. */
    val PRESETS = listOf(5, 10, 15, 25, 30, 45, 60)
    private const val DEFAULT_MINUTES = 30

    data class State(
        val minutes: Int = DEFAULT_MINUTES,
        val remaining: Int = DEFAULT_MINUTES * 60,
        val running: Boolean = false,
    ) {
        /** Started and not reset: running or paused. */
        val active: Boolean get() = running || remaining < minutes * 60
    }

    /** What one run counted, as desktop's summary reports it. */
    data class Summary(
        val minutes: Int,
        val cards: Int,
        val pdfPages: Int,
        val pdfDocuments: Int,
        val epubPages: Int,
        val epubDocuments: Int,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The summary of a run that just ended, until a reader has shown it. */
    val finished = MutableStateFlow<Summary?>(null)

    private var ticking: Job? = null
    private var cards = 0
    private val pdfPages = mutableSetOf<Pair<Long, Int>>()
    private val epubPages = mutableSetOf<Pair<Long, Int>>()

    /** Chooses a preset; ignored while running, as on desktop. */
    fun pick(minutes: Int) {
        if (_state.value.running) return
        _state.value = State(minutes, minutes * 60)
    }

    fun start() {
        val current = _state.value
        if (current.running) return
        _state.value = current.copy(remaining = if (current.remaining <= 0) current.minutes * 60 else current.remaining, running = true)
        ticking =
            Ankrimento.readerScope.launch {
                while (isActive && _state.value.running) {
                    delay(1_000)
                    val now = _state.value
                    if (!now.running) break
                    val left = (now.remaining - 1).coerceAtLeast(0)
                    _state.value = now.copy(remaining = left)
                    if (left == 0) finish()
                }
            }
    }

    fun pause() {
        ticking?.cancel()
        _state.value = _state.value.copy(running = false)
    }

    /** Back to the chosen preset; the counts so far are dropped, as desktop's reset drops them. */
    fun reset() {
        ticking?.cancel()
        _state.value = State(_state.value.minutes, _state.value.minutes * 60)
        clearCounts()
    }

    /** Counts only while running (desktop's record_card_answered). */
    fun cardAnswered() {
        if (_state.value.running) cards++
    }

    fun pdfPageRead(
        cardId: Long,
        page: Int,
    ) {
        if (_state.value.running && cardId > 0 && page > 0) synchronized(pdfPages) { pdfPages += cardId to page }
    }

    fun epubPageRead(
        cardId: Long,
        pageIndex: Int,
    ) {
        if (_state.value.running && cardId > 0 && pageIndex >= 0) synchronized(epubPages) { epubPages += cardId to pageIndex }
    }

    private suspend fun finish() {
        val minutes = _state.value.minutes
        _state.value = State(minutes, minutes * 60)
        val summary =
            Summary(
                minutes = minutes,
                cards = cards,
                pdfPages = synchronized(pdfPages) { pdfPages.size },
                pdfDocuments = synchronized(pdfPages) { pdfPages.map { it.first }.toSet().size },
                epubPages = synchronized(epubPages) { epubPages.size },
                epubDocuments = synchronized(epubPages) { epubPages.map { it.first }.toSet().size },
            )
        clearCounts()
        // Desktop's timer_completion_beep, on unless turned off.
        val tone =
            !Ankrimento.isStarted ||
                runCatching { withCol { Ankrimento.settingsValues() }.optBoolean("timer_completion_beep", true) }.getOrDefault(true)
        if (tone) playTone()
        finished.value = summary
    }

    private fun clearCounts() {
        cards = 0
        synchronized(pdfPages) { pdfPages.clear() }
        synchronized(epubPages) { epubPages.clear() }
    }

    /** Desktop beeps; a short notification tone here. */
    private fun playTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MS)
            Ankrimento.readerScope.launch {
                delay(TONE_MS.toLong() + 100)
                tone.release()
            }
        }
    }

    private const val TONE_VOLUME = 80
    private const val TONE_MS = 400
}
