// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * Desktop's Search ALL: one search across highlights, annotations, the cards made from
 * documents, document text, text in images and cards. Results are grouped as desktop groups
 * them; one opens its PDF page (with the words highlighted), its EPUB section or its card.
 */
class SearchAllActivity : ComponentActivity() {
    private var query by mutableStateOf("")
    private var results by mutableStateOf<JSONObject?>(null)
    private var filters by mutableStateOf<JSONObject?>(null)
    private var searching by mutableStateOf(false)
    private var pending: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IncrementoTheme { Search() } }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@SearchAllActivity, this)
                    Ankrimento.searchAllFilters()
                }
            }.onSuccess { filters = it }
                .onFailure { error -> toast(error.message ?: error.javaClass.simpleName) }
        }
    }

    /** Searches [text] after a short pause in typing (at once when [now]), as desktop's search while typing. */
    private fun search(
        text: String,
        now: Boolean = false,
    ) {
        query = text
        pending?.cancel()
        if (text.trim().length < MIN_CHARS) {
            results = null
            searching = false
            return
        }
        pending =
            lifecycleScope.launch {
                if (!now) delay(TYPING_PAUSE_MS)
                searching = true
                runCatching { withCol { Ankrimento.searchAll(text.trim()) } }
                    .onSuccess { if (text == query) results = it }
                    .onFailure { error ->
                        Timber.w(error, "Search ALL failed")
                        toast(error.message ?: error.javaClass.simpleName)
                    }
                searching = false
            }
    }

    private fun setFilter(
        name: String,
        enabled: Boolean,
    ) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.setSearchAllFilter(name, enabled)
                    Ankrimento.searchAllFilters()
                }
            }.onSuccess {
                filters = it
                search(query, now = true)
            }
        }
    }

    private fun open(item: JSONObject) {
        val cardId = item.optLong("card_id")
        if (cardId <= 0) return
        val kind = item.optString("kind")
        lifecycleScope.launch {
            val intent =
                when (kind) {
                    "pdf", "epub" -> ReaderActivity.intentAt(this@SearchAllActivity, kind, cardId, item.optInt("position"), query.trim())
                    else -> {
                        val reader = withCol { Ankrimento.cardReader(cardId) }
                        ReaderActivity.intent(this@SearchAllActivity, reader.ifEmpty { "item" }, cardId, session = false)
                    }
                }
            intent?.let(::startActivity)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Search() {
        val focus = remember { FocusRequester() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { search(it) },
                            placeholder = { Text(stringResource(R.string.ankrimento_search_all_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search(query, now = true) }),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
        ) { padding ->
            LaunchedEffect(Unit) { focus.requestFocus() }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
                item { Filters() }
                val current = results
                when {
                    searching && current == null ->
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    current == null ->
                        item { Hint(if (query.isEmpty()) R.string.ankrimento_search_all_intro else R.string.ankrimento_search_all_short) }
                    current.getJSONArray("sections").length() == 0 -> item { Hint(R.string.ankrimento_search_all_none) }
                    else -> {
                        val sections = current.getJSONArray("sections")
                        for (s in 0 until sections.length()) {
                            val section = sections.getJSONObject(s)
                            item(key = "s$s") {
                                Text(
                                    section.getString("heading"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                                )
                            }
                            val groups = section.getJSONArray("groups")
                            for (g in 0 until groups.length()) {
                                val group = groups.getJSONObject(g)
                                group.optString("title").takeIf { !group.isNull("title") && it.isNotEmpty() }?.let { title ->
                                    item(key = "s$s-g$g") {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                        )
                                    }
                                }
                                val items = group.getJSONArray("items")
                                for (i in 0 until items.length()) {
                                    val result = items.getJSONObject(i)
                                    item(key = "s$s-g$g-i$i") {
                                        ListItem(
                                            overlineContent = { Text(result.optString("label")) },
                                            headlineContent = {
                                                Text(result.optString("snippet"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            },
                                            modifier = Modifier.clickable { open(result) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Desktop's search filters, as chips; they are saved, as desktop saves them. */
    @Composable
    private fun Filters() {
        val current = filters ?: return
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTERS.forEach { (name, label) ->
                val on = current.optBoolean(name)
                FilterChip(selected = on, onClick = { setFilter(name, !on) }, label = { Text(stringResource(label)) })
            }
        }
    }

    @Composable
    private fun Hint(text: Int) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }

    companion object {
        /** Desktop's minimum (_MIN_SEARCH_CHARS). */
        private const val MIN_CHARS = 3
        private const val TYPING_PAUSE_MS = 400L

        private val FILTERS =
            listOf(
                "cards" to R.string.ankrimento_search_all_cards,
                "pdf_highlights" to R.string.ankrimento_search_all_pdf_highlights,
                "epub_highlights" to R.string.ankrimento_search_all_epub_highlights,
                "annotations" to R.string.ankrimento_search_all_annotations,
                "pdf_sources" to R.string.ankrimento_search_all_pdf_sources,
                "epub_sources" to R.string.ankrimento_search_all_epub_sources,
                "pdf_content" to R.string.ankrimento_search_all_pdf_content,
                "epub_content" to R.string.ankrimento_search_all_epub_content,
                "image_ocr" to R.string.ankrimento_search_all_image_ocr,
            )

        fun intent(context: Context) = Intent(context, SearchAllActivity::class.java)
    }
}
