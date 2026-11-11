// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.LocalFileScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * A local file card (desktop's "Add Local File"): the file kept in the profile, opened with another app. The screen
 * says what the file is and, for a card made on desktop that only points at a path there, offers to keep a copy here;
 * the sync engine then shares that copy with every device.
 */
class LocalFileActivity : ReaderActivity() {
    private var file by mutableStateOf<Ankrimento.LocalFile?>(null)

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) attach(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setReaderContent(View(this))
        overlay = {
            file?.let { shown ->
                LocalFileScreen(
                    file = shown,
                    onOpen = { LocalFileAccess.open(this@LocalFileActivity, shown) },
                    onShare = { LocalFileAccess.share(this@LocalFileActivity, shown) },
                    onAttach = { pickFile.launch(arrayOf("*/*")) },
                )
            }
        }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.localFile(cardId) } }
                .onSuccess { file = it }
                .onFailure { Timber.w(it, "Reading the local file card failed") }
        }
    }

    /** Keeps the picked file as this card's file on this device. */
    private fun attach(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                DocumentImport.import(this@LocalFileActivity, uri, "file") { path, _ ->
                    Ankrimento.attachLocalFile(cardId, path)
                    cardId
                }
            }.onSuccess { refresh() }
                .onFailure { error ->
                    Timber.w(error, "Attaching a file failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    companion object {
        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "file", cardId, session = false)!!
    }
}

/**
 * Hands a local file to another app through the app's FileProvider: read access for that one file, nothing else. The
 * app gets a copy under the file's own name, not the stored one (which carries an id to keep names unique), so a shared
 * attachment or a file manager shows "Week 3 handout.pdf". Only the file last handed over is kept.
 */
internal object LocalFileAccess {
    fun open(
        activity: ReaderActivity,
        file: Ankrimento.LocalFile,
    ) = start(activity, file, share = false)

    fun share(
        activity: ReaderActivity,
        file: Ankrimento.LocalFile,
    ) = start(activity, file, share = true)

    private fun start(
        activity: ReaderActivity,
        file: Ankrimento.LocalFile,
        share: Boolean,
    ) {
        if (!file.available || !file.canOpen) return
        activity.lifecycleScope.launch {
            val handed =
                runCatching { withContext(Dispatchers.IO) { copyForHandOver(activity, file) } }
                    .onFailure { Timber.w(it, "Preparing a local file to hand over failed") }
                    .getOrNull() ?: return@launch
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.apkgfileprovider", handed)
            val grant =
                if (share) {
                    Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri)
                } else {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
                }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(Intent.createChooser(grant, file.name))
            } catch (error: ActivityNotFoundException) {
                Timber.i(error, "No app can open %s", file.mime)
                activity.showToast(R.string.ankrimento_local_file_no_app)
            }
        }
    }

    /** The file under its own name in the cache; the folder is emptied first so one copy is ever kept. */
    private fun copyForHandOver(
        context: Context,
        file: Ankrimento.LocalFile,
    ): File {
        val folder = File(context.cacheDir, "ankrimento-open")
        folder.deleteRecursively()
        folder.mkdirs()
        val target = File(folder, displayName(file.name))
        File(file.path).copyTo(target, overwrite = true)
        return target
    }

    /** A name another app can show and store: no path separators or characters file systems refuse. */
    internal fun displayName(name: String): String =
        name
            .filterNot { it.code < 32 }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifEmpty { "file" }
}
