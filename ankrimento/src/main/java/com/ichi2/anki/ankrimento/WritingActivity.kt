// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A writing topic's Markdown, edited in place and saved as desktop's writing
 * dock saves it (backups per the settings, daily word counts).
 */
class WritingActivity : ReaderActivity() {
    private lateinit var editor: EditText
    private var loaded = false
    private var savedText = ""
    private var pendingSave: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        editor =
            EditText(this).apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(padding, padding, padding, padding)
                background = null
            }
        setReaderContent(editor)
        lifecycleScope.launch {
            savedText = withCol { Ankrimento.writingOpen(cardId) }
            editor.setText(savedText)
            editor.setSelection(savedText.length)
            loaded = true
            editor.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) = Unit

                    override fun afterTextChanged(s: Editable?) = scheduleSave()
                },
            )
        }
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave =
            lifecycleScope.launch {
                delay(AUTOSAVE_DELAY_MS)
                save()
            }
    }

    private suspend fun save() {
        val text = editor.text.toString()
        if (!loaded || text == savedText) return
        runCatching { withCol { Ankrimento.writingSave(cardId, text) } }
            .onSuccess { words ->
                savedText = text
                subtitle = resources.getQuantityString(R.plurals.ankrimento_writing_words, words, words)
            }.onFailure { Timber.w(it, "Saving writing failed") }
    }

    override fun onPause() {
        super.onPause()
        pendingSave?.cancel()
        lifecycleScope.launch { save() }
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 2_000L

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "writing", cardId, session = false)!!
    }
}
