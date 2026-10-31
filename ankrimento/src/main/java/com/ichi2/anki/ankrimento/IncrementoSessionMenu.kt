// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "Start Incremento session" in the deck list menu, like desktop's "Start
 * Incremental Learning": Incremento picks the cards with its saved scheduler
 * settings, fills the "Incremento Session" deck (which becomes the current
 * deck) and asks for the reviewer, which [openReviewer] opens.
 */
class IncrementoSessionMenu(
    private val activity: ComponentActivity,
    private val openReviewer: () -> Unit,
) : MenuProvider {
    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect { event ->
                    when (event) {
                        Ankrimento.Event.EnterReview -> openReviewer()
                        is Ankrimento.Event.Message -> toast(event.text)
                    }
                }
            }
        }
    }

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
            CollectionManager.withCol { Ankrimento.start(activity, this) }
            withContext(Dispatchers.IO) { Ankrimento.startSession() }
        }.onFailure { error ->
            Timber.w(error, "Incremento session failed")
            toast(activity.getString(R.string.ankrimento_session_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
