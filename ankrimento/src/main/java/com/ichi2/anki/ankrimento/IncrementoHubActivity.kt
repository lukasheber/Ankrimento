// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Incremento's library, the mobile counterpart of desktop's Document
 * Bookshelf: what was read last on this device first, then every document
 * by kind, each opening in its reader for reading (sessions review them).
 * Add brings in a PDF, an EPUB, a web or video link, or a new writing.
 */
class IncrementoHubActivity : ComponentActivity() {
    private var documents by mutableStateOf<List<Ankrimento.LibraryEntry>?>(null)
    private var recent by mutableStateOf<List<Ankrimento.LibraryEntry>>(emptyList())
    private var filter by mutableStateOf<String?>(null)
    private var adding by mutableStateOf(false)
    private var askLink by mutableStateOf(false)
    private var askWriting by mutableStateOf(false)

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri !=
                null
            ) {
                lifecycleScope.launch {
                    importDocument {
                        DocumentImport.import(
                            this@IncrementoHubActivity,
                            uri,
                            "document.pdf",
                            Ankrimento::importPdf,
                        )
                    }
                }
            }
        }

    private val pickEpub =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri !=
                null
            ) {
                lifecycleScope.launch {
                    importDocument {
                        DocumentImport.import(
                            this@IncrementoHubActivity,
                            uri,
                            "book.epub",
                            Ankrimento::importEpub,
                        )
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IncrementoTheme { Library() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { load() }
        }
    }

    private suspend fun load() {
        val entries =
            runCatching {
                withCol {
                    Ankrimento.start(this@IncrementoHubActivity, this)
                    Ankrimento.library()
                }
            }.getOrElse { error ->
                Timber.w(error, "Loading the library failed")
                toast(error.message ?: error.javaClass.simpleName)
                emptyList()
            }
        val byId = entries.associateBy { it.cardId }
        recent =
            RecentDocuments
                .cardIds(this)
                .mapNotNull { byId[it] }
                .filterNot { it.done }
                .take(RECENT)
        documents = entries.sortedWith(compareBy({ it.done }, { it.priority }, { it.title.lowercase() }))
    }

    private fun open(entry: Ankrimento.LibraryEntry) {
        ReaderActivity.intent(this, entry.kind, entry.cardId, session = false)?.let(::startActivity)
    }

    private suspend fun importDocument(import: suspend () -> Pair<Long, String>) {
        runCatching { import() }
            .onSuccess { (_, title) ->
                toast(getString(R.string.ankrimento_pdf_added, title))
                load()
            }.onFailure { error ->
                Timber.w(error, "Adding a document failed")
                toast(getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName))
            }
    }

    private fun addLink(url: String) {
        lifecycleScope.launch {
            importDocument {
                val cardId = withCol { Ankrimento.importLink(url, "") }
                cardId to url
            }
        }
    }

    private fun addWriting(title: String) {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.createWriting(title) } }
                .onSuccess { cardId -> startActivity(WritingActivity.intent(this@IncrementoHubActivity, cardId)) }
                .onFailure { toast(getString(R.string.ankrimento_pdf_add_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Library() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ankrimento_library)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(IncrementoIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.ankrimento_add)) },
                )
            },
        ) { padding ->
            val all = documents
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item { SectionTitle(R.string.ankrimento_continue_reading) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recent, key = { it.cardId }) { entry -> RecentCard(entry) }
                        }
                    }
                }
                item { SectionTitle(R.string.ankrimento_all_documents) }
                item { KindFilters() }
                val shown = all?.filter { filter == null || it.kind == filter }.orEmpty()
                if (all != null && shown.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.ankrimento_library_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(shown, key = { it.cardId }) { entry -> DocumentRow(entry) }
            }
        }
        if (adding) AddSheet()
        if (askLink) {
            TextPrompt(R.string.ankrimento_add_link, R.string.ankrimento_link_hint, onDismiss = { askLink = false }) { addLink(it) }
        }
        if (askWriting) {
            TextPrompt(R.string.ankrimento_new_writing, R.string.ankrimento_new_writing_title, onDismiss = { askWriting = false }) {
                addWriting(it)
            }
        }
    }

    @Composable
    private fun SectionTitle(label: Int) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
    }

    @Composable
    private fun RecentCard(entry: Ankrimento.LibraryEntry) {
        ElevatedCard(onClick = { open(entry) }, modifier = Modifier.width(200.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(kindIcon(entry.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(details(entry, withPriority = false), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }

    @Composable
    private fun KindFilters() {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(null) + KINDS.keys.toList()) { kind ->
                FilterChip(
                    selected = filter == kind,
                    onClick = { filter = kind },
                    label = { Text(stringResource(kind?.let { KINDS.getValue(it) } ?: R.string.ankrimento_kind_all)) },
                )
            }
        }
    }

    @Composable
    private fun DocumentRow(entry: Ankrimento.LibraryEntry) {
        ListItem(
            headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(details(entry, withPriority = true), maxLines = 1) },
            leadingContent = { Icon(kindIcon(entry.kind), contentDescription = null) },
            trailingContent =
                if (entry.done) {
                    { Text(stringResource(R.string.ankrimento_done), style = MaterialTheme.typography.labelMedium) }
                } else {
                    null
                },
            modifier = Modifier.clickable { open(entry) },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AddSheet() {
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                AddOption(IncrementoIcons.Document, R.string.ankrimento_add_pdf_file) { pickPdf.launch(arrayOf("application/pdf")) }
                AddOption(IncrementoIcons.Book, R.string.ankrimento_add_epub_file) { pickEpub.launch(arrayOf("application/epub+zip")) }
                AddOption(IncrementoIcons.Web, R.string.ankrimento_add_link) { askLink = true }
                AddOption(IncrementoIcons.Edit, R.string.ankrimento_new_writing) { askWriting = true }
            }
        }
    }

    @Composable
    private fun AddOption(
        icon: ImageVector,
        label: Int,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(label)) },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier =
                Modifier.clickable {
                    adding = false
                    onClick()
                },
        )
    }

    @Composable
    private fun TextPrompt(
        title: Int,
        hint: Int,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit,
    ) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismiss()
                        onConfirm(text.trim())
                    },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun details(
        entry: Ankrimento.LibraryEntry,
        withPriority: Boolean,
    ): String {
        val parts =
            listOfNotNull(
                stringResource(KINDS.getValue(entry.kind)),
                entry.position.takeIf { it.isNotEmpty() },
                if (withPriority) stringResource(R.string.ankrimento_priority_chip, entry.priority.roundToInt()) else null,
            )
        return parts.joinToString(" · ")
    }

    private fun kindIcon(kind: String): ImageVector =
        when (kind) {
            "pdf" -> IncrementoIcons.Document
            "epub" -> IncrementoIcons.Book
            "web" -> IncrementoIcons.Web
            "video" -> IncrementoIcons.Video
            else -> IncrementoIcons.Edit
        }

    companion object {
        private const val RECENT = 8

        private val KINDS =
            linkedMapOf(
                "pdf" to R.string.ankrimento_kind_pdf,
                "epub" to R.string.ankrimento_kind_epub,
                "web" to R.string.ankrimento_kind_web,
                "video" to R.string.ankrimento_kind_video,
                "writing" to R.string.ankrimento_kind_writing,
            )

        fun intent(context: Context) = Intent(context, IncrementoHubActivity::class.java)
    }
}
