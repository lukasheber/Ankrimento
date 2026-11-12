// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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

    /** Cards left in the session already under way (0 for none): Start would not begin a new one by itself. */
    private var inProgress = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        inProgress = intent.getIntExtra(EXTRA_REMAINING, 0)
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
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                            if (inProgress > 0) {
                                Button(
                                    onClick = {
                                        setResult(RESULT_RESUME)
                                        finish()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(pluralStringResource(R.plurals.ankrimento_session_resume, inProgress, inProgress)) }
                                TextButton(
                                    onClick = {
                                        setResult(RESULT_OK)
                                        finish()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ankrimento_session_preview_start_new)) }
                            } else {
                                Button(
                                    onClick = {
                                        setResult(RESULT_OK)
                                        finish()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ankrimento_session_preview_start)) }
                            }
                        }
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
                                modifier = Modifier.clickable { open(entry) },
                                headlineContent = {
                                    Text(
                                        entry.title.ifEmpty { "#${entry.cardId}" },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            entry.type.takeIf { it.isNotEmpty() },
                                            when (entry.modeKey) {
                                                "priority" -> stringResource(R.string.ankrimento_preview_mode_priority)
                                                "random" -> stringResource(R.string.ankrimento_preview_mode_random)
                                                else -> entry.mode.takeIf { it.isNotEmpty() }
                                            },
                                            entry.tag.takeIf { entry.hasTag && it.isNotEmpty() },
                                        ).joinToString(" · "),
                                    )
                                },
                            )
                        }
                    }
            }
        }
    }

    /** The card, looked at in its own reader without answering it. */
    private fun open(entry: Ankrimento.PreviewEntry) {
        lifecycleScope.launch {
            val reader = withCol { Ankrimento.cardReader(entry.cardId) }
            ReaderActivity
                .intent(
                    this@SessionPreviewActivity,
                    reader.ifEmpty { "item" },
                    entry.cardId,
                    session = false,
                )?.let(::startActivity)
        }
    }

    @Composable
    private fun Summary(shown: Ankrimento.SessionPreview) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (inProgress > 0) {
                Text(
                    pluralStringResource(R.plurals.ankrimento_session_preview_in_progress, inProgress, inProgress),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
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
            if (shown.entries.isNotEmpty() && inProgress == 0) {
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
        /** Result: carry on with the session under way. */
        const val RESULT_RESUME = RESULT_FIRST_USER
        private const val EXTRA_REMAINING = "remaining"

        fun intent(
            context: Context,
            remaining: Int = 0,
        ) = Intent(context, SessionPreviewActivity::class.java).putExtra(EXTRA_REMAINING, remaining)
    }
}
