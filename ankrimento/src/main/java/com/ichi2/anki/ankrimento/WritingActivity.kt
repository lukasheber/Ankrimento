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
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A writing topic's Markdown, edited in place and saved as desktop's writing
 * dock saves it (backups per the settings, daily word counts). Desktop's
 * formatting buttons sit above the keyboard; Preview shows desktop's rendering.
 */
class WritingActivity : ReaderActivity() {
    private lateinit var editor: EditText
    private lateinit var preview: WebView
    private var loaded = false
    private var savedText = ""
    private var pendingSave: Job? = null
    private var buttons = emptyList<Ankrimento.WritingButton>()
    private var previewing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        editor =
            EditText(this).apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                // Room under the text for the formatting buttons.
                setPadding(padding, padding, padding, padding * 5)
                background = null
                // The screen around follows the system's dark mode (IncrementoTheme); the text must too.
                setTextColor(if (darkMode()) DARK_TEXT else LIGHT_TEXT)
            }
        preview = WebView(this).apply { visibility = View.GONE }
        setReaderContent(
            FrameLayout(this).apply {
                addView(editor, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                addView(preview, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            },
        )
        showTools()
        lifecycleScope.launch {
            val (text, toolbar) = withCol { Ankrimento.writingOpen(cardId) to Ankrimento.writingToolbar() }
            savedText = text
            buttons = toolbar
            editor.setText(savedText)
            editor.setSelection(savedText.length)
            loaded = true
            showToolbar()
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

    private fun showTools() {
        tools =
            listOf(
                ReaderTool(
                    getString(if (previewing) R.string.ankrimento_writing_edit else R.string.ankrimento_writing_preview),
                    IncrementoIcons.TextFormat,
                ) { setPreviewing(!previewing) },
            )
    }

    /** Desktop's formatting buttons in a row above the keyboard, each named for screen readers by its tooltip. */
    private fun showToolbar() {
        overlay =
            if (previewing || buttons.isEmpty()) {
                null
            } else {
                {
                    Surface(
                        tonalElevation = 3.dp,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .imePadding()
                                .navigationBarsPadding(),
                    ) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            buttons.forEach { button ->
                                FilledTonalButton(
                                    onClick = { format(button.kind) },
                                    modifier = Modifier.semantics { contentDescription = button.description },
                                ) { Text(button.label, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
    }

    /** Puts the button's Markdown in place of the selection, as desktop's toolbar does, and keeps typing after it. */
    private fun format(kind: String) {
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val selected = editor.text.substring(start, end)
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.writingTransform(kind, selected) } }
                .onSuccess { replacement ->
                    editor.text.replace(start, end, replacement)
                    editor.setSelection(start + replacement.length)
                    editor.requestFocus()
                }.onFailure { Timber.w(it, "Formatting the writing failed") }
        }
    }

    private fun setPreviewing(on: Boolean) {
        previewing = on
        showTools()
        showToolbar()
        if (!on) {
            preview.visibility = View.GONE
            editor.visibility = View.VISIBLE
            return
        }
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(editor.windowToken, 0)
        val text = editor.text.toString()
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.writingPreview(text) } }
                .onSuccess { html ->
                    preview.loadDataWithBaseURL(null, PREVIEW_HEAD + html, "text/html", "utf-8", null)
                    editor.visibility = View.GONE
                    preview.visibility = View.VISIBLE
                }.onFailure {
                    Timber.w(it, "Previewing the writing failed")
                    toast(it.message ?: it.javaClass.simpleName)
                    setPreviewing(false)
                }
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

    private fun darkMode() =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        preview.destroy()
        super.onDestroy()
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 2_000L

        /** Material 3's on-surface text colours, dark and light. */
        private const val DARK_TEXT = 0xFFE6E1E5.toInt()
        private const val LIGHT_TEXT = 0xFF1C1B1F.toInt()

        /** A phone's width for desktop's preview, which brings its own styles. */
        private const val PREVIEW_HEAD =
            "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>body { margin: 16px; overflow-wrap: break-word; }</style>"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "writing", cardId, session = false)!!
    }
}
