// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The knowledge tree, as desktop's tree browser shows it: documents, topics
 * and the extracts made from them, branch by branch. Branches fold and
 * unfold; a node opens in its reader, starts a session on its branch, takes
 * a new priority, leads to its parent, or postpones its branch as desktop's
 * postpone dialog does. The search finds nodes by title or note text; a
 * reader opens the tree at its own card.
 */
class KnowledgeTreeActivity : ComponentActivity() {
    private var nodes by mutableStateOf<List<Ankrimento.TreeNode>?>(null)
    private var collapsed by mutableStateOf<Set<Long>>(emptySet())
    private var chosen by mutableStateOf<Ankrimento.TreeNode?>(null)
    private var editingPriority by mutableStateOf<Ankrimento.TreeNode?>(null)

    /** The node revealed (from a reader, a search, Go to parent), marked until another is. */
    private var selected by mutableStateOf<Long?>(null)
    private var scrollTo by mutableStateOf<Long?>(null)
    private var pendingReveal: Long? = null
    private var searching by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var hits by mutableStateOf<List<Ankrimento.TreeHit>?>(null)
    private var searchJob: Job? = null
    private var postponing by mutableStateOf<Pair<Ankrimento.TreeNode, Ankrimento.PostponePlan>?>(null)
    private var emptyBranch by mutableStateOf<String?>(null)

    /** A branch session was asked for: its outcome comes as an event. */
    private var branchStarting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingReveal = intent.getLongExtra(EXTRA_REVEAL, 0L).takeIf { it > 0 }
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
        pendingReveal?.let {
            pendingReveal = null
            reveal(it)
        }
    }

    /** Unfolds the branches above [cardId], marks it and scrolls to it. */
    private fun reveal(cardId: Long) {
        val all = nodes ?: return
        val parents = all.associate { it.cardId to it.parentId }
        if (cardId !in parents) {
            toast(getString(R.string.ankrimento_tree_not_found))
            return
        }
        var unfolded = collapsed
        var parent = parents[cardId]
        var steps = 0
        while (parent != null && steps++ < MAX_DEPTH) {
            unfolded = unfolded - parent
            parent = parents[parent]
        }
        collapsed = unfolded
        selected = cardId
        scrollTo = cardId
    }

    private fun search(text: String) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) {
            hits = null
            return
        }
        searchJob =
            lifecycleScope.launch {
                delay(SEARCH_DELAY_MS)
                hits =
                    runCatching { withCol { Ankrimento.treeSearch(text.trim()) } }
                        .onFailure { Timber.w(it, "Knowledge tree search failed") }
                        .getOrDefault(emptyList())
            }
    }

    private fun closeSearch() {
        searchJob?.cancel()
        searching = false
        query = ""
        hits = null
    }

    /** Upstream's simulation first; Postpone applies the same plan. */
    private fun planPostpone(node: Ankrimento.TreeNode) {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.treePostponePlan(node.cardId) } }
                .onSuccess { postponing = node to it }
                .onFailure { error ->
                    Timber.w(error, "Simulating a branch postpone failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    private fun postpone(node: Ankrimento.TreeNode) {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.treePostponeBranch(node.cardId) } }
                .onSuccess { message ->
                    toast(message)
                    load()
                }.onFailure { error ->
                    Timber.w(error, "Postponing a branch failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
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
        emptyBranch = message
    }

    /** Why the branch has nothing to study now, as the session's dialog says it. */
    @Composable
    private fun EmptyBranchDialog(message: String) {
        AlertDialog(
            onDismissRequest = { emptyBranch = null },
            title = { Text(stringResource(R.string.ankrimento_session_empty_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { emptyBranch = null }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = {
                TextButton(onClick = {
                    emptyBranch = null
                    startActivity(IncrementoSettingsActivity.intent(this))
                }) { Text(stringResource(R.string.ankrimento_settings)) }
            },
        )
    }

    private fun toast(text: String) {
        if (text.isNotEmpty()) showToast(text)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Tree() {
        val listState = rememberLazyListState()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { if (searching) SearchField() else Text(stringResource(R.string.ankrimento_knowledge_tree_title)) },
                    navigationIcon = {
                        IconButton(onClick = { if (searching) closeSearch() else finish() }) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                    actions = {
                        if (!searching) {
                            IconButton(onClick = { searching = true }) {
                                Icon(IncrementoIcons.Search, contentDescription = stringResource(R.string.ankrimento_tree_search_hint))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            val all = nodes
            val found = hits
            when {
                all == null -> Unit
                searching && found != null ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                top = padding.calculateTopPadding(),
                                bottom =
                                    padding.calculateBottomPadding() + 24.dp,
                            ),
                    ) {
                        if (found.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.ankrimento_search_all_none),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(found, key = { it.cardId }) { hit ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        hit.title.ifEmpty { "#${hit.cardId}" },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = { Text(hit.reason) },
                                leadingContent = {
                                    Icon(
                                        if (hit.kind ==
                                            "item"
                                        ) {
                                            IncrementoIcons.Check
                                        } else {
                                            IncrementoIcons.Document
                                        },
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        closeSearch()
                                        reveal(hit.cardId)
                                    },
                            )
                        }
                    }
                all.isEmpty() ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ankrimento_knowledge_tree_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                else ->
                    LazyColumn(
                        state = listState,
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
            LaunchedEffect(scrollTo, all, searching) {
                val target = scrollTo ?: return@LaunchedEffect
                if (all == null || searching) return@LaunchedEffect
                val index = visible(all).indexOfFirst { it.cardId == target }
                if (index >= 0) listState.animateScrollToItem(index)
                scrollTo = null
            }
        }
        chosen?.let { NodeSheet(it) }
        postponing?.let { (node, plan) -> PostponeDialog(node, plan) }
        emptyBranch?.let { EmptyBranchDialog(it) }
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
    private fun SearchField() {
        val focus = remember { FocusRequester() }
        TextField(
            value = query,
            onValueChange = ::search,
            placeholder = { Text(stringResource(R.string.ankrimento_tree_search_hint)) },
            singleLine = true,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    @Composable
    private fun PostponeDialog(
        node: Ankrimento.TreeNode,
        plan: Ankrimento.PostponePlan,
    ) {
        AlertDialog(
            onDismissRequest = { postponing = null },
            title = { Text(stringResource(R.string.ankrimento_tree_postpone_title)) },
            text = {
                Column {
                    Text(node.title.ifEmpty { "#${node.cardId}" }, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(
                        if (plan.preset.isEmpty()) {
                            stringResource(R.string.ankrimento_tree_postpone_defaults)
                        } else {
                            stringResource(R.string.ankrimento_tree_postpone_preset, plan.preset)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    plan.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = plan.count > 0,
                    onClick = {
                        postponing = null
                        postpone(node)
                    },
                ) { Text(stringResource(R.string.ankrimento_postpone)) }
            },
            dismissButton = { TextButton(onClick = { postponing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
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
                    .background(if (node.cardId == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
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
                SheetAction(IncrementoIcons.Next, R.string.ankrimento_tree_postpone) { planPostpone(node) }
                node.parentId?.let { parent ->
                    SheetAction(IncrementoIcons.Previous, R.string.ankrimento_tree_go_to_parent) { reveal(parent) }
                }
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
        private const val MAX_DEPTH = 64
        private const val SEARCH_DELAY_MS = 250L
        private const val EXTRA_REVEAL = "reveal"

        /** The tree, opened at [revealCardId] when given (its branch unfolded and the node marked). */
        fun intent(
            context: Context,
            revealCardId: Long? = null,
        ) = Intent(context, KnowledgeTreeActivity::class.java).apply { revealCardId?.let { putExtra(EXTRA_REVEAL, it) } }
    }
}
