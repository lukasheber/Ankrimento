// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import com.ichi2.anki.ankrimento.ui.PriorityDialog
import com.ichi2.anki.common.destinations.DeferredNavigation
import com.ichi2.anki.common.destinations.ReviewDeckDestination
import com.ichi2.anki.common.destinations.toIntent
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The knowledge tree, as desktop's tree browser shows it: documents, topics
 * and the extracts made from them, branch by branch. Branches fold and
 * unfold; a node opens in its reader, starts a session on its branch, or
 * takes a new priority.
 */
class KnowledgeTreeActivity : ComponentActivity() {
    private var nodes by mutableStateOf<List<Ankrimento.TreeNode>?>(null)
    private var collapsed by mutableStateOf<Set<Long>>(emptySet())
    private var chosen by mutableStateOf<Ankrimento.TreeNode?>(null)
    private var editingPriority by mutableStateOf<Ankrimento.TreeNode?>(null)

    /** A branch session was asked for: its outcome comes as an event. */
    private var branchStarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IncrementoTheme { Tree() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { load() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Ankrimento.events.collect { event ->
                    when (event) {
                        Ankrimento.Event.EnterReview ->
                            if (branchStarting) {
                                branchStarting = false
                                startActivity(with(DeferredNavigation) { ReviewDeckDestination.CurrentDeck.toIntent() })
                            }
                        is Ankrimento.Event.Message -> if (branchStarting) explainEmptyBranch(event.text) else toast(event.text)
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun load() {
        nodes =
            runCatching {
                withCol {
                    Ankrimento.start(this@KnowledgeTreeActivity, this)
                    Ankrimento.knowledgeTree()
                }
            }.getOrElse { error ->
                Timber.w(error, "Loading the knowledge tree failed")
                toast(error.message ?: error.javaClass.simpleName)
                emptyList()
            }
    }

    /** The nodes shown: everything but the descendants of folded branches. */
    private fun visible(all: List<Ankrimento.TreeNode>): List<Ankrimento.TreeNode> {
        val shown = mutableListOf<Ankrimento.TreeNode>()
        var hiddenBelow: Int? = null
        for (node in all) {
            if (hiddenBelow != null && node.depth > hiddenBelow) continue
            hiddenBelow = null
            shown += node
            if (node.cardId in collapsed) hiddenBelow = node.depth
        }
        return shown
    }

    private fun hasChildren(
        all: List<Ankrimento.TreeNode>,
        node: Ankrimento.TreeNode,
    ): Boolean {
        val index = all.indexOf(node)
        return index >= 0 && all.getOrNull(index + 1)?.let { it.depth > node.depth } == true
    }

    private fun open(node: Ankrimento.TreeNode) {
        lifecycleScope.launch {
            val reader = withCol { Ankrimento.cardReader(node.cardId) }
            val intent = ReaderActivity.intent(this@KnowledgeTreeActivity, reader.ifEmpty { "item" }, node.cardId, session = false)
            intent?.let(::startActivity)
        }
    }

    private fun studyBranch(node: Ankrimento.TreeNode) {
        lifecycleScope.launch {
            runCatching {
                branchStarting = true
                val started = withCol { Ankrimento.startBranchSession(node.cardId) }
                if (!started) {
                    branchStarting = false
                    toast(getString(R.string.ankrimento_knowledge_tree_empty))
                }
            }.onFailure { error ->
                branchStarting = false
                toast(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Upstream found nothing to study in the branch: say so, with the settings that decide it. */
    private fun explainEmptyBranch(message: String) {
        branchStarting = false
        AlertDialog
            .Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.ankrimento_session_empty_title)
            .setMessage(message)
            .setPositiveButton(R.string.ankrimento_settings) { _, _ -> startActivity(IncrementoSettingsActivity.intent(this)) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(text: String) {
        if (text.isNotEmpty()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Tree() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ankrimento_knowledge_tree_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
        ) { padding ->
            val all = nodes
            when {
                all == null -> Unit
                all.isEmpty() ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ankrimento_knowledge_tree_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                top = padding.calculateTopPadding(),
                                bottom =
                                    padding.calculateBottomPadding() + 24.dp,
                            ),
                    ) {
                        items(visible(all), key = { it.cardId }) { node -> NodeRow(node, hasChildren(all, node)) }
                    }
            }
        }
        chosen?.let { NodeSheet(it) }
        editingPriority?.let { node ->
            PriorityDialog(node.priority ?: DEFAULT_PRIORITY, onDismiss = { editingPriority = null }) { priority ->
                editingPriority = null
                lifecycleScope.launch {
                    withCol { Ankrimento.setPriority(node.cardId, priority) }
                    load()
                }
            }
        }
    }

    @Composable
    private fun NodeRow(
        node: Ankrimento.TreeNode,
        children: Boolean,
    ) {
        val folded = node.cardId in collapsed
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { chosen = node }
                    .padding(start = (8 + node.depth.coerceAtMost(8) * 20).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (children) {
                IconButton(onClick = { collapsed = if (folded) collapsed - node.cardId else collapsed + node.cardId }) {
                    Icon(
                        IncrementoIcons.ExpandMore,
                        contentDescription = stringResource(if (folded) R.string.ankrimento_expand else R.string.ankrimento_collapse),
                        modifier = Modifier.rotate(if (folded) -90f else 0f),
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Icon(
                if (node.kind == "item") IncrementoIcons.Check else IncrementoIcons.Document,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                Text(node.title.ifEmpty { "#${node.cardId}" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (node.kind == "item") R.string.ankrimento_extract_item else R.string.ankrimento_extract_as_topic),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            node.priority?.let {
                Text(stringResource(R.string.ankrimento_priority_chip, it.roundToInt()), style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NodeSheet(node: Ankrimento.TreeNode) {
        ModalBottomSheet(onDismissRequest = { chosen = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    node.title.ifEmpty { "#${node.cardId}" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SheetAction(IncrementoIcons.Book, R.string.ankrimento_open) { open(node) }
                SheetAction(IncrementoIcons.Tree, R.string.ankrimento_study_branch) { studyBranch(node) }
                SheetAction(IncrementoIcons.Tune, R.string.ankrimento_priority_title) { editingPriority = node }
            }
        }
    }

    @Composable
    private fun SheetAction(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: Int,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(label)) },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier =
                Modifier.clickable {
                    chosen = null
                    onClick()
                },
        )
    }

    companion object {
        private const val DEFAULT_PRIORITY = 50.0

        fun intent(context: Context) = Intent(context, KnowledgeTreeActivity::class.java)
    }
}
