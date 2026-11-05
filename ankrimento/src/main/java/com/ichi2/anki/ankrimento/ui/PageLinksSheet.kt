// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.Ankrimento
import com.ichi2.anki.ankrimento.R

/**
 * The page's links to other PDF pages (desktop's page links): each says where it
 * leads, or where it comes from, and opens there; it can be removed; the page can
 * be linked to another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageLinksSheet(
    page: Int,
    links: List<Ankrimento.PageLink>,
    onOpen: (Ankrimento.PageLink) -> Unit,
    onRemove: (Ankrimento.PageLink) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.ankrimento_page_links_title, page),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (links.isEmpty()) {
                Text(
                    stringResource(R.string.ankrimento_page_links_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            LazyColumn {
                items(links, key = { "${it.direction}:${it.id}" }) { link ->
                    val where =
                        stringResource(
                            if (link.direction == "incoming") R.string.ankrimento_page_link_from else R.string.ankrimento_page_link_to,
                            link.title,
                            link.page,
                        )
                    ListItem(
                        headlineContent = { Text(where) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(link) }) {
                                Icon(IncrementoIcons.Delete, contentDescription = stringResource(R.string.ankrimento_page_link_remove))
                            }
                        },
                        modifier =
                            Modifier
                                .clickable(enabled = link.available) { onOpen(link) }
                                .alpha(if (link.available) 1f else 0.5f),
                    )
                }
            }
            FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Icon(IncrementoIcons.Link, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.ankrimento_page_link_add))
            }
        }
    }
}

/** The PDFs a page can be linked to, as desktop's bookshelf offers them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkTargetSheet(
    documents: List<Ankrimento.LibraryEntry>,
    onPick: (Ankrimento.LibraryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.ankrimento_page_link_choose_document),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(documents, key = { it.cardId }) { document ->
                    ListItem(
                        headlineContent = { Text(document.title) },
                        supportingContent = document.position.takeIf { it.isNotEmpty() }?.let { position -> { Text(position) } },
                        leadingContent = { Icon(IncrementoIcons.Document, contentDescription = null) },
                        modifier = Modifier.clickable { onPick(document) },
                    )
                }
            }
        }
    }
}
