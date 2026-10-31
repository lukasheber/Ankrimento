// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Add to Incremento" in Android's share sheet: a shared video link becomes
 * an Incremento Video topic (YouTube, Vimeo), as desktop's Add Video does; a
 * page is read as an article (clean text, like a book) or kept as the live
 * page (desktop's Web Page). Titles are looked up when the link has none.
 */
class ShareToIncrementoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Patterns.WEB_URL.matcher(text).let { if (it.find()) it.group() else null }
        if (url == null || !Ankrimento.isSupported) {
            Toast.makeText(this, R.string.ankrimento_share_no_link, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        if (Ankrimento.isVideoLink(url)) {
            add { Ankrimento.importLink(url, title) }
            return
        }
        // A page: read as an article (clean text, like a book) or kept as the live page.
        AlertDialog
            .Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(R.string.ankrimento_share_label)
            .setItems(arrayOf(getString(R.string.ankrimento_add_article), getString(R.string.ankrimento_add_live_page))) { _, which ->
                if (which == 0) add { Ankrimento.importWebDocument(url).first } else add { Ankrimento.importLink(url, title) }
            }.setOnCancelListener { finish() }
            .show()
    }

    private fun add(import: () -> Long) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@ShareToIncrementoActivity, this)
                    Ankrimento.cardTitle(import())
                }
            }.onSuccess { added ->
                Toast.makeText(this@ShareToIncrementoActivity, getString(R.string.ankrimento_pdf_added, added), Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Timber.w(error, "Adding a shared page failed")
                val message = getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName)
                Toast.makeText(this@ShareToIncrementoActivity, message, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
