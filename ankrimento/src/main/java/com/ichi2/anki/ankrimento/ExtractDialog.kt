// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.content.Context
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "New card from extract": the mobile counterpart of desktop's Add Card dock
 * for an extract. Field formatting from the document is kept unless the text
 * is edited, in which case the edited text is used as plain text.
 */
internal object ExtractDialog {
    /**
     * Shows the pending extract from [sourceCardId] and adds it on confirmation.
     * [onAdded] receives JavaScript refreshing the reader (may be empty).
     */
    fun showFor(
        activity: ComponentActivity,
        sourceCardId: Long,
        onAdded: (script: String) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val draft = withCol { Ankrimento.extractDraft(sourceCardId) }
            show(
                activity,
                draft,
                onAdd = { result ->
                    activity.lifecycleScope.launch {
                        runCatching {
                            withCol {
                                Ankrimento.createExtract(sourceCardId, result.fields, result.noteType, result.isTopic, result.priority)
                            }
                        }.onSuccess { script ->
                            onAdded(script)
                            Toast.makeText(activity, R.string.ankrimento_extract_added, Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            Timber.w(error, "Adding an extract failed")
                            val message =
                                activity.getString(
                                    R.string.ankrimento_extract_failed,
                                    error.message ?: error.javaClass.simpleName,
                                )
                            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDiscard = { activity.lifecycleScope.launch { withCol { Ankrimento.discardExtract(sourceCardId) } } },
            )
        }
    }

    data class Result(
        val fields: List<String>,
        val noteType: String,
        val isTopic: Boolean,
        val priority: Double,
    )

    /**
     * @param onAdd the user confirmed the card
     * @param onDiscard the user dropped the extract
     * (dismissing otherwise keeps the draft for further extracts)
     */
    fun show(
        context: Context,
        draft: Ankrimento.ExtractDraft,
        onAdd: (Result) -> Unit,
        onDiscard: () -> Unit,
    ) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
            }
        val fieldEditors =
            draft.fields.mapIndexed { index, html ->
                val label = if (index == 0) R.string.ankrimento_extract_front else R.string.ankrimento_extract_back
                layout.addView(TextView(context).apply { setText(label) })
                val editor = FieldEditor(EditText(context), html)
                editor.view.setText(editor.shown)
                editor.view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                layout.addView(editor.view)
                editor
            }
        val noteTypes =
            Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, draft.noteTypes)
                setSelection(draft.noteTypes.indexOf(draft.noteType).coerceAtLeast(0))
            }
        layout.addView(TextView(context).apply { setText(R.string.ankrimento_extract_note_type) })
        layout.addView(noteTypes)
        val topic =
            CheckBox(context).apply {
                setText(R.string.ankrimento_extract_topic)
                isChecked = draft.isTopic
            }
        layout.addView(topic)
        layout.addView(TextView(context).apply { setText(R.string.ankrimento_extract_priority) })
        val priority =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(draft.priority.toString())
            }
        layout.addView(priority)

        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankrimento_extract_title)
            .setView(
                ScrollView(context).apply { addView(layout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) },
            ).setPositiveButton(R.string.ankrimento_extract_add) { _, _ ->
                onAdd(
                    Result(
                        fields = fieldEditors.map { it.html() },
                        noteType = noteTypes.selectedItem as String,
                        isTopic = topic.isChecked,
                        priority =
                            priority.text
                                .toString()
                                .toDoubleOrNull()
                                ?.coerceIn(0.0, 100.0) ?: draft.priority,
                    ),
                )
            }.setNeutralButton(R.string.ankrimento_extract_keep, null)
            .setNegativeButton(R.string.ankrimento_extract_discard) { _, _ -> onDiscard() }
            .show()
    }

    /** Edits one field; the original HTML is kept unless its text was changed. */
    private class FieldEditor(
        val view: EditText,
        private val originalHtml: String,
    ) {
        val shown: String = Html.fromHtml(originalHtml, Html.FROM_HTML_MODE_COMPACT).toString().trim()

        fun html(): String {
            val text = view.text.toString().trim()
            return if (text == shown) originalHtml else TextUtils.htmlEncode(text).replace("\n", "<br>")
        }
    }
}
