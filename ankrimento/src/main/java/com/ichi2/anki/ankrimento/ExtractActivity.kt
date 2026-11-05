// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.BatchSheet
import com.ichi2.anki.ankrimento.ui.ExtractChoice
import com.ichi2.anki.ankrimento.ui.ExtractSheet
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The extract sheet outside a reader: for text selected on a card in
 * AnkiDroid's reviewer (desktop's extract from the reviewer card, the card
 * as its source), or for a card made from scratch with a priority (desktop's
 * Add Card dock outside an extract).
 */
class ExtractActivity : ComponentActivity() {
    private var draft by mutableStateOf<Ankrimento.ExtractDraft?>(null)
    private var batchLabels by mutableStateOf<Map<String, String>>(emptyMap())

    /** Text to make several cards from (desktop's Batch Q/A), with the sheet's choices; null when not batching. */
    private var batch by mutableStateOf<Pair<ExtractChoice, String>?>(null)

    /** The card the extract comes from; 0 for a card from scratch. */
    private val sourceCardId by lazy { intent.getLongExtra(EXTRA_SOURCE, 0L) }

    /** The kind its note type is remembered for (see [ExtractNoteTypes]). */
    private val kind get() = if (sourceCardId != 0L) "card" else "scratch"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IncrementoTheme {
                batch?.let { (choice, text) ->
                    BatchSheet(
                        labels = batchLabels,
                        initialText = text,
                        preview = { withCol { Ankrimento.extractBatchPreview(it) } },
                        onCreate = { pairs ->
                            batch = null
                            addBatch(choice, pairs)
                        },
                        onDismiss = { batch = null },
                    )
                } ?: draft?.let { shown ->
                    ExtractSheet(
                        batchLabel = batchLabels["button"],
                        onBatch = { choice, text -> batch = choice to text },
                        draft = shown,
                        onAdd = ::add,
                        onLater = null,
                        onKeep = {
                            if (sourceCardId != 0L) toast(getString(R.string.ankrimento_extract_kept))
                            finish()
                        },
                        onDiscard = {
                            if (sourceCardId != 0L) lifecycleScope.launch { withCol { Ankrimento.discardExtract(sourceCardId) } }
                            finish()
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            draft =
                runCatching {
                    withCol {
                        Ankrimento.start(this@ExtractActivity, this)
                        batchLabels = Ankrimento.extractBatchLabels()
                        if (sourceCardId != 0L) Ankrimento.extractDraft(sourceCardId) else Ankrimento.newCardDefaults()
                    }
                }.map { ExtractNoteTypes.choose(this@ExtractActivity, kind, it) }
                    .getOrElse { error ->
                        Timber.w(error, "Opening the extract sheet failed")
                        toast(error.message ?: error.javaClass.simpleName)
                        finish()
                        return@launch
                    }
        }
    }

    private fun add(choice: ExtractChoice) {
        ExtractNoteTypes.remember(this, kind, choice.noteType)
        lifecycleScope.launch {
            runCatching {
                withCol {
                    if (sourceCardId != 0L) {
                        Ankrimento.createExtract(sourceCardId, choice.fields, choice.noteType, choice.isTopic, choice.priority)
                        getString(R.string.ankrimento_extract_added_under, Ankrimento.cardTitle(sourceCardId))
                    } else {
                        Ankrimento.createCard(choice.fields, choice.noteType, choice.isTopic, choice.priority, choice.deck)
                        getString(R.string.ankrimento_new_card_added, choice.deck)
                    }
                }
            }.onSuccess { toast(it) }
                .onFailure { error ->
                    Timber.w(error, "Adding a card failed")
                    toast(getString(R.string.ankrimento_extract_failed, error.message ?: error.javaClass.simpleName))
                }
            finish()
        }
    }

    /** Desktop's Batch Q/A: one card per valid pair, under the source card or from scratch in the chosen deck. */
    private fun addBatch(
        choice: ExtractChoice,
        pairs: String,
    ) {
        ExtractNoteTypes.remember(this, kind, choice.noteType)
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.extractBatchCreate(
                        sourceCardId,
                        pairs,
                        choice.noteType,
                        choice.isTopic,
                        choice.priority,
                        choice.deck,
                    )
                }
            }.onSuccess { (_, summary) -> toast(summary) }
                .onFailure { error ->
                    Timber.w(error, "Adding batch cards failed")
                    toast(getString(R.string.ankrimento_extract_failed, error.message ?: error.javaClass.simpleName))
                }
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_SOURCE = "source"

        /** The sheet for [sourceCardId]'s pending extract, or for a card from scratch when null. */
        fun intent(
            context: Context,
            sourceCardId: Long? = null,
        ) = Intent(context, ExtractActivity::class.java).apply { sourceCardId?.let { putExtra(EXTRA_SOURCE, it) } }
    }
}
