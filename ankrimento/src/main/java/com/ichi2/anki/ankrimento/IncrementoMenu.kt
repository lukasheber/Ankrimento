// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.net.Uri
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Incremento's entries in the deck list menu.
 *
 * - "Start Incremento session", like desktop's "Start Incremental Learning":
 *   Incremento picks the cards with its saved scheduler settings, fills the
 *   "Incremento Session" deck (which becomes the current deck) and asks for
 *   the reviewer, which [openReviewer] opens.
 * - "Add PDF to Incremento", like desktop's Add PDF.
 */
class IncrementoMenu(
    private val activity: ComponentActivity,
    private val openReviewer: () -> Unit,
) : MenuProvider {
    private val pickPdf =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) activity.lifecycleScope.launch { importPdf(uri) }
        }

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect { event ->
                    when (event) {
                        Ankrimento.Event.EnterReview -> openReviewer()
                        is Ankrimento.Event.Message -> toast(event.text)
                        else -> Unit
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
        menu.add(Menu.NONE, R.id.ankrimento_add_pdf, Menu.CATEGORY_SECONDARY, R.string.ankrimento_add_pdf)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.ankrimento_start_session -> activity.lifecycleScope.launch { startSession() }
            R.id.ankrimento_add_pdf -> pickPdf.launch(arrayOf("application/pdf"))
            else -> return false
        }
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

    private suspend fun importPdf(uri: Uri) {
        Timber.i("Adding a PDF to Incremento")
        runCatching {
            val name = displayName(uri) ?: "document.pdf"
            // Incremento copies the file into its own PDF folder; stage it first.
            val staged = File(activity.cacheDir, "ankrimento-import").apply { mkdirs() }.resolve(File(name).name)
            withContext(Dispatchers.IO) {
                activity.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "cannot read $uri" }
                    staged.outputStream().use { input.copyTo(it) }
                }
            }
            try {
                CollectionManager.withCol {
                    Ankrimento.start(activity, this)
                    Ankrimento.importPdf(staged.absolutePath, staged.nameWithoutExtension)
                }
            } finally {
                staged.delete()
            }
            staged.nameWithoutExtension
        }.onSuccess { title ->
            toast(activity.getString(R.string.ankrimento_pdf_added, title))
        }.onFailure { error ->
            Timber.w(error, "Adding a PDF failed")
            toast(activity.getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
