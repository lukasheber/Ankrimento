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

    /** The card the extract comes from; 0 for a card from scratch. */
    private val sourceCardId by lazy { intent.getLongExtra(EXTRA_SOURCE, 0L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IncrementoTheme {
                draft?.let { shown ->
                    ExtractSheet(
                        draft = shown,
                        onAdd = ::add,
                        onLater = null,
                        onKeep = ::finish,
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
                        if (sourceCardId != 0L) Ankrimento.extractDraft(sourceCardId) else Ankrimento.newCardDefaults()
                    }
                }.getOrElse { error ->
                    Timber.w(error, "Opening the extract sheet failed")
                    toast(error.message ?: error.javaClass.simpleName)
                    finish()
                    return@launch
                }
        }
    }

    private fun add(choice: ExtractChoice) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    if (sourceCardId != 0L) {
                        Ankrimento.createExtract(sourceCardId, choice.fields, choice.noteType, choice.isTopic, choice.priority)
                    } else {
                        Ankrimento.createCard(choice.fields, choice.noteType, choice.isTopic, choice.priority, choice.deck)
                    }
                }
            }.onSuccess { toast(getString(R.string.ankrimento_extract_added)) }
                .onFailure { error ->
                    Timber.w(error, "Adding a card failed")
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
