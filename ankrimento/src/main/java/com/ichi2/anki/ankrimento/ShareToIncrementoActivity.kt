// SPDX-License-Identifier: GPL-3.0-or-later

// The platform dialog: this screen has a platform translucent theme, under which AppCompat's would crash.
@file:SuppressLint("AvoidAlertDialogUsage")

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
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
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null && Ankrimento.isSupported) {
            addSharedFile(stream)
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Patterns.WEB_URL.matcher(text).let { if (it.find()) it.group() else null }
        if (url == null || !Ankrimento.isSupported) {
            showToast(R.string.ankrimento_share_no_link)
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
            .setItems(
                arrayOf(
                    getString(R.string.ankrimento_add_article),
                    getString(R.string.ankrimento_add_saved_website),
                    getString(R.string.ankrimento_add_live_page),
                ),
            ) { _, which ->
                when (which) {
                    0 -> add { Ankrimento.importWebDocument(url).first }
                    1 -> add { Ankrimento.importWebSnapshot(url, website = true).first }
                    else -> add { Ankrimento.importLink(url, title) }
                }
            }.setOnCancelListener { finish() }
            .show()
    }

    /** A shared file: a local file card, or a PDF or EPUB document when the file is one and that is what is wanted. */
    private fun addSharedFile(uri: Uri) {
        lifecycleScope.launch {
            val name = DocumentImport.displayName(this@ShareToIncrementoActivity, uri) ?: "file"
            val kind =
                runCatching {
                    withCol {
                        Ankrimento.start(this@ShareToIncrementoActivity, this)
                        Ankrimento.localFileSuggests(name)
                    }
                }.getOrDefault("")
            if (kind.isEmpty()) {
                storeSharedFile(uri, "")
                return@launch
            }
            AlertDialog
                .Builder(this@ShareToIncrementoActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(R.string.ankrimento_add_as_document_title)
                .setItems(
                    arrayOf(
                        getString(if (kind == "pdf") R.string.ankrimento_add_as_pdf else R.string.ankrimento_add_as_epub),
                        getString(R.string.ankrimento_add_as_file),
                    ),
                ) { _, which -> storeSharedFile(uri, if (which == 0) kind else "") }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun storeSharedFile(
        uri: Uri,
        kind: String,
    ) {
        lifecycleScope.launch {
            runCatching {
                when (kind) {
                    "pdf" -> DocumentImport.import(this@ShareToIncrementoActivity, uri, "document.pdf", Ankrimento::importPdf)
                    "epub" -> DocumentImport.import(this@ShareToIncrementoActivity, uri, "book.epub", Ankrimento::importEpub)
                    else ->
                        DocumentImport.import(this@ShareToIncrementoActivity, uri, "file") { path, title ->
                            Ankrimento.importLocalFile(path, title).first
                        }
                }
            }.onSuccess { (_, title) -> showToast(getString(R.string.ankrimento_pdf_added, title)) }
                .onFailure { error ->
                    Timber.w(error, "Adding a shared file failed")
                    showToast(getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName))
                }
            finish()
        }
    }

    private fun add(import: () -> Long) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@ShareToIncrementoActivity, this)
                    Ankrimento.cardTitle(import())
                }
            }.onSuccess { added ->
                this@ShareToIncrementoActivity.showToast(getString(R.string.ankrimento_pdf_added, added))
            }.onFailure { error ->
                Timber.w(error, "Adding a shared page failed")
                val message = getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName)
                this@ShareToIncrementoActivity.showToast(message)
            }
            finish()
        }
    }
}
