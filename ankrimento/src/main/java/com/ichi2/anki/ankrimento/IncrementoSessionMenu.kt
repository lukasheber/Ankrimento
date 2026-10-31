// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Start Incremento session" in the deck list menu: picks cards with
 * Incremento's scheduler, fills the "Incremento Session" deck (which becomes
 * the current deck) and calls [openReviewer].
 */
class IncrementoSessionMenu(
    private val activity: ComponentActivity,
    private val openReviewer: () -> Unit,
) : MenuProvider {
    override fun onCreateMenu(
        menu: Menu,
        menuInflater: MenuInflater,
    ) {
        if (!Ankrimento.isSupported) return
        menu.add(Menu.NONE, R.id.ankrimento_start_session, Menu.CATEGORY_SECONDARY, R.string.ankrimento_start_session)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.ankrimento_start_session) return false
        activity.lifecycleScope.launch { startSession() }
        return true
    }

    private suspend fun startSession() {
        Timber.i("Starting Incremento session")
        runCatching {
            CollectionManager.withCol {
                Ankrimento.start(activity, this)
                val cardIds = Ankrimento.selectSession()
                if (cardIds.isEmpty()) null else Ankrimento.buildSessionDeck(cardIds)
            }
        }.onSuccess { deckId ->
            if (deckId == null) {
                toast(activity.getString(R.string.ankrimento_no_cards))
            } else {
                openReviewer()
            }
        }.onFailure { error ->
            Timber.w(error, "Incremento session failed")
            toast(activity.getString(R.string.ankrimento_session_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
