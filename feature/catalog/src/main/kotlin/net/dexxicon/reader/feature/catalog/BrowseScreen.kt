package net.dexxicon.reader.feature.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.ContentFilterChips
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.component.ViewModeToggle
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus

/** What the long-press menu on a Browse card needs to act on and render. */
private data class BrowseItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onSetStatus: (ReadingStatus) -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenBook: (AggregatedBook) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = if (state.viewMode == BookViewMode.GRID) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } ?: return@derivedStateOf false
            state.books.isNotEmpty() && last >= state.books.size - 6
        }
    }
    androidx.compose.runtime.LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    fun actionsFor(book: AggregatedBook): BrowseItemActions {
        val c = book.primary
        // Status/mark-read applies to every copy so the servers don't disagree.
        val targets = book.copies.map { it.serverId to it.bookId }
        return BrowseItemActions(
            downloadStatus = if (book.downloadedIn(overlays)) DownloadStatus.DONE else null,
            onMarkRead = { viewModel.markRead(targets) },
            onMarkUnread = { viewModel.markUnread(targets) },
            onSetStatus = { viewModel.setReadingStatus(targets, it) },
            onDetails = { onOpenBook(book) },
            onDownloadOrRemove = {
                viewModel.downloadOrRemove(
                    c.serverId,
                    c.bookId,
                    if (book.downloadedIn(overlays)) DownloadStatus.DONE else null,
                )
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search titles, authors…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )

            ContentFilterChips(state.filter, viewModel::onFilterSelected)

            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val next = BookSort.entries[(state.sort.ordinal + 1) % BookSort.entries.size]
                FilterChip(
                    selected = false,
                    onClick = { viewModel.onSortSelected(next) },
                    label = { Text("Sort: ${state.sort.name.lowercase()}") },
                )
                ViewModeToggle(state.viewMode, viewModel::toggleViewMode)
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> CenterBox { CircularProgressIndicator() }
                    state.error != null && state.books.isEmpty() -> CenterBox {
                        Text(
                            state.error ?: "",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    state.books.isEmpty() -> CenterBox {
                        Text(
                            when {
                                state.query.isNotBlank() -> "No books match “${state.query}”."
                                state.filter != ContentFilter.ALL -> "No ${state.filter.label.lowercase()} here."
                                else -> "Add a server in Settings to start browsing."
                            },
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    state.viewMode == BookViewMode.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        gridItems(state.books, key = { it.key }) { book ->
                            BrowseGridCard(
                                book = book,
                                progress = book.progressFrom(overlays),
                                downloaded = book.downloadedIn(overlays),
                                onClick = { onOpenBook(book) },
                                actions = actionsFor(book),
                            )
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.books, key = { it.key }) { book ->
                            BrowseRow(
                                book = book,
                                progress = book.progressFrom(overlays),
                                downloaded = book.downloadedIn(overlays),
                                onClick = { onOpenBook(book) },
                                actions = actionsFor(book),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun AggregatedBook.progressFrom(overlays: BookOverlays): Float? =
    copies.mapNotNull { overlays.progress["${it.serverId}::${it.bookId}"] }.maxOrNull()

private fun AggregatedBook.downloadedIn(overlays: BookOverlays): Boolean =
    copies.any { "${it.serverId}::${it.bookId}" in overlays.downloaded }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: BrowseItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val libraries = book.copies.size
    val subtitle = buildString {
        if (book.authorLine.isNotBlank()) append(book.authorLine)
        append(if (isEmpty()) "" else " · ")
        append(book.format.name.lowercase())
        if (libraries > 1) append(" · On $libraries libraries")
        if (pct != null) append(" · $pct% read")
    }
    Box {
        ListItem(
            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Box(Modifier.width(44.dp)) {
                    CoverImage(book.coverUrl, contentDescription = null, progress = progress, downloaded = downloaded)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = "${book.title}, ${book.authorLine}, $subtitle"
                },
        )
        BrowseMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseGridCard(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: BrowseItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val secondary = if (book.copies.size > 1) "On ${book.copies.size} libraries" else book.authorLine
    val label = buildString {
        append(book.title)
        if (book.authorLine.isNotBlank()) append(", ${book.authorLine}")
        if (pct != null) append(", $pct% read")
        if (downloaded) append(", downloaded")
    }
    Box {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) { contentDescription = label },
        ) {
            CoverImage(
                coverUrl = book.coverUrl,
                contentDescription = null,
                progress = progress,
                downloaded = downloaded,
                format = book.format,
            )
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (secondary.isNotBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BrowseMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@Composable
private fun BrowseMenu(expanded: Boolean, onDismiss: () -> Unit, actions: BrowseItemActions) {
    BookContextMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        downloadStatus = actions.downloadStatus,
        currentStatus = null,
        onMarkRead = actions.onMarkRead,
        onMarkUnread = actions.onMarkUnread,
        onSetStatus = actions.onSetStatus,
        onDetails = actions.onDetails,
        onDownloadOrRemove = actions.onDownloadOrRemove,
    )
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    // Scrollable even when it fits, so pull-to-refresh still works on the empty/error states.
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) { content() }
}
