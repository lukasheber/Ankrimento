// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Incremento's session settings (desktop's Basic view). */
internal object SessionSettingsDialog {
    fun show(
        context: Context,
        settings: Ankrimento.SessionSettings,
        onSave: (Ankrimento.SessionSettings) -> Unit,
    ) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
            }
        layout.addView(TextView(context).apply { setText(R.string.ankrimento_settings_card_count) })
        val cardCount =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(settings.cardCount.toString())
            }
        layout.addView(cardCount)
        val topics = percentage(context, layout, R.string.ankrimento_settings_topics, settings.topicsPercent)
        val documents = percentage(context, layout, R.string.ankrimento_settings_documents, settings.documentsPercent)
        val random = percentage(context, layout, R.string.ankrimento_settings_random, settings.randomPercent)
        val includeNew = checkBox(context, layout, R.string.ankrimento_settings_new, settings.includeNew)
        val includeLearning = checkBox(context, layout, R.string.ankrimento_settings_learning, settings.includeLearning)
        val includeDue = checkBox(context, layout, R.string.ankrimento_settings_due, settings.includeDue)
        val autoRefill = checkBox(context, layout, R.string.ankrimento_settings_auto_refill, settings.autoRefill)

        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankrimento_session_settings)
            .setView(
                ScrollView(context).apply { addView(layout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) },
            ).setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(
                    Ankrimento.SessionSettings(
                        cardCount =
                            cardCount.text
                                .toString()
                                .toIntOrNull()
                                ?.coerceIn(1, 9999) ?: settings.cardCount,
                        topicsPercent = topics.progress,
                        documentsPercent = documents.progress,
                        randomPercent = random.progress,
                        includeNew = includeNew.isChecked,
                        includeLearning = includeLearning.isChecked,
                        includeDue = includeDue.isChecked,
                        autoRefill = autoRefill.isChecked,
                    ),
                )
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A 0–100 slider whose label shows the current value. */
    private fun percentage(
        context: Context,
        layout: LinearLayout,
        label: Int,
        value: Int,
    ): SeekBar {
        val text = TextView(context)
        val bar =
            SeekBar(context).apply {
                max = 100
                progress = value
            }

        fun update() {
            text.text = context.getString(label, bar.progress)
        }
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) = update()

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            },
        )
        update()
        layout.addView(text)
        layout.addView(bar)
        return bar
    }

    private fun checkBox(
        context: Context,
        layout: LinearLayout,
        label: Int,
        checked: Boolean,
    ): CheckBox =
        CheckBox(context)
            .apply {
                setText(label)
                isChecked = checked
            }.also { layout.addView(it) }
}
