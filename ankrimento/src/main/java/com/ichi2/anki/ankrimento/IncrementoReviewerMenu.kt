// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Incremento's reviewer actions, as on desktop: Done, Postpone and
 * "Revisit in…" for topics, and Priority for any card.
 *
 * @param currentCardId the card on screen, if any
 */
class IncrementoReviewerMenu(
    private val activity: ComponentActivity,
    private val currentCardId: () -> Long?,
) : MenuProvider {
    override fun onCreateMenu(
        menu: Menu,
        menuInflater: MenuInflater,
    ) {
        if (!Ankrimento.isStarted) return
        val cardId = currentCardId() ?: return
        if (Ankrimento.shownTopicCardId == cardId) {
            menu.add(Menu.NONE, R.id.ankrimento_topic_done, Menu.CATEGORY_SECONDARY, R.string.ankrimento_topic_done)
            menu.add(Menu.NONE, R.id.ankrimento_topic_postpone, Menu.CATEGORY_SECONDARY, R.string.ankrimento_topic_postpone)
            menu.addSubMenu(Menu.NONE, Menu.NONE, Menu.CATEGORY_SECONDARY, R.string.ankrimento_topic_revisit).apply {
                add(Menu.NONE, R.id.ankrimento_topic_revisit_3, Menu.NONE, R.string.ankrimento_topic_revisit_3)
                add(Menu.NONE, R.id.ankrimento_topic_revisit_6, Menu.NONE, R.string.ankrimento_topic_revisit_6)
                add(Menu.NONE, R.id.ankrimento_topic_revisit_12, Menu.NONE, R.string.ankrimento_topic_revisit_12)
            }
        }
        menu.add(Menu.NONE, R.id.ankrimento_priority, Menu.CATEGORY_SECONDARY, R.string.ankrimento_priority)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        val cardId = currentCardId() ?: return false
        when (menuItem.itemId) {
            R.id.ankrimento_topic_done -> act { Ankrimento.topicDone(cardId) }
            R.id.ankrimento_topic_postpone -> act { Ankrimento.topicPostpone(cardId) }
            R.id.ankrimento_topic_revisit_3 -> act { Ankrimento.topicRevisit(cardId, 3) }
            R.id.ankrimento_topic_revisit_6 -> act { Ankrimento.topicRevisit(cardId, 6) }
            R.id.ankrimento_topic_revisit_12 -> act { Ankrimento.topicRevisit(cardId, 12) }
            R.id.ankrimento_priority -> editPriority(cardId)
            else -> return false
        }
        return true
    }

    /** Runs an action on the collection queue and shows its message; the reviewer refreshes itself. */
    private fun act(action: () -> String) {
        activity.lifecycleScope.launch {
            runCatching { withCol { action() } }
                .onSuccess { toast(it) }
                .onFailure { error ->
                    Timber.w(error, "Incremento reviewer action failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    private fun editPriority(cardId: Long) {
        activity.lifecycleScope.launch {
            val current = withCol { Ankrimento.priority(cardId) }
            val input =
                EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(current.toString())
                }
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.ankrimento_priority)
                .setMessage(R.string.ankrimento_priority_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val priority =
                        input.text
                            .toString()
                            .toDoubleOrNull()
                            ?.coerceIn(0.0, 100.0) ?: return@setPositiveButton
                    activity.lifecycleScope.launch { withCol { Ankrimento.setPriority(cardId, priority) } }
                }.setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
