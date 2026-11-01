// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The next session before it starts, as desktop's session dialog previews it:
 * the cards upstream's scheduler picks with the current settings, each with
 * its type, how it was picked and its tag. Start returns to the deck list,
 * which starts the session with exactly these cards (while the settings stay
 * the same).
 */
class SessionPreviewActivity : ComponentActivity() {
    private var preview by mutableStateOf<Ankrimento.SessionPreview?>(null)
    private var failed by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IncrementoTheme { Preview() } }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@SessionPreviewActivity, this)
                    Ankrimento.sessionPreview()
                }
            }.onSuccess { preview = it }
                .onFailure { error ->
                    Timber.w(error, "Session preview failed")
                    failed = error.message ?: error.javaClass.simpleName
                }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Preview() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ankrimento_session_preview_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
            bottomBar = {
                val shown = preview
                if (shown != null && shown.entries.isNotEmpty()) {
                    Surface(tonalElevation = 3.dp) {
                        Button(
                            onClick = {
                                setResult(RESULT_OK)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        ) { Text(stringResource(R.string.ankrimento_session_preview_start)) }
                    }
                }
            },
        ) { padding ->
            val shown = preview
            when {
                failed != null ->
                    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ankrimento_session_failed, failed!!))
                    }
                shown == null ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        item { Summary(shown) }
                        items(shown.entries, key = { it.cardId }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        entry.title.ifEmpty { "#${entry.cardId}" },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        listOf(entry.type, entry.mode, entry.tag).filter { it.isNotEmpty() }.joinToString(" · "),
                                    )
                                },
                            )
                        }
                    }
            }
        }
    }

    @Composable
    private fun Summary(shown: Ankrimento.SessionPreview) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.ankrimento_session_preview_count, shown.entries.size, shown.target),
                style = MaterialTheme.typography.titleMedium,
            )
            val note =
                when {
                    shown.entries.isEmpty() -> R.string.ankrimento_session_preview_empty
                    shown.entries.size < shown.target -> R.string.ankrimento_session_preview_limited
                    else -> null
                }
            note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            if (shown.entries.isNotEmpty()) {
                Text(
                    stringResource(R.string.ankrimento_session_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, SessionPreviewActivity::class.java)
    }
}
