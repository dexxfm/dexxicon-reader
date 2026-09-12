package net.dexxicon.reader.shared.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.ContentFilterChips
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.component.ViewModeToggle
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader

/** What the long-press menu on a Library card needs to act on and render. */
private data class LibraryItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onSetStatus: (ReadingStatus) -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

/**
 * Phase 4 Stage D (issue #133) — the one Library composable, ported as-is from native's
 * `feature/catalog/BrowseScreen.kt` (same search/sort/filter/grid-list toggle/infinite
 * scroll/context menu), reading from [LibraryState] instead of a Hilt `BrowseViewModel`. A
 * cover tap resolves to either [onOpenBook] or [LibraryState.openBook] depending on
 * [LibraryUiState.coverTapAction] — see [net.dexxicon.reader.core.designsystem.component]
 * for [CoverTapAction]'s own doc comment on why Home's shelves never consult this setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryState,
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: OnOpenReader,
    modifier: Modifier = Modifier,
) {
    val uiState by state.uiState.collectAsState()
    val overlays by state.overlays.collectAsState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    fun onTapCover(book: AggregatedBook) = when (uiState.coverTapAction) {
        CoverTapAction.OPEN_DETAILS -> onOpenBook(book)
        CoverTapAction.OPEN_BOOK -> state.openBook(book, onOpenReader)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = if (uiState.viewMode == BookViewMode.GRID) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } ?: return@derivedStateOf false
            uiState.books.isNotEmpty() && last >= uiState.books.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) state.loadMore()
    }

    fun actionsFor(book: AggregatedBook): LibraryItemActions {
        val c = book.primary
        // Status/mark-read applies to every copy so the servers don't disagree.
        val targets = book.copies.map { it.serverId to it.bookId }
        return LibraryItemActions(
            downloadStatus = if (book.downloadedIn(overlays)) DownloadStatus.DONE else null,
            onMarkRead = { state.markRead(targets) },
            onMarkUnread = { state.markUnread(targets) },
            onSetStatus = { state.setReadingStatus(targets, it) },
            onDetails = { onOpenBook(book) },
            onDownloadOrRemove = {
                state.downloadOrRemove(
                    c.serverId,
                    c.bookId,
                    if (book.downloadedIn(overlays)) DownloadStatus.DONE else null,
                )
            },
        )
    }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Library") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = uiState.query,
                onValueChange = state::onQueryChange,
                placeholder = { Text("Search titles, authors…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )

            ContentFilterChips(uiState.filter, state::onFilterSelected)

            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val next = BookSort.entries[(uiState.sort.ordinal + 1) % BookSort.entries.size]
                FilterChip(
                    selected = false,
                    onClick = { state.onSortSelected(next) },
                    label = { Text("Sort: ${uiState.sort.name.lowercase()}") },
                )
                ViewModeToggle(uiState.viewMode, state::toggleViewMode)
            }

            PullToRefreshBox(
                isRefreshing = uiState.refreshing,
                onRefresh = state::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.loading -> CenterBox { CircularProgressIndicator() }
                    uiState.error != null && uiState.books.isEmpty() -> CenterBox {
                        Text(
                            uiState.error ?: "",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    uiState.books.isEmpty() -> CenterBox {
                        Text(
                            when {
                                uiState.query.isNotBlank() -> "No books match “${uiState.query}”."
                                uiState.filter != ContentFilter.ALL -> "No ${uiState.filter.label.lowercase()} here."
                                else -> "Add a server in Settings to start browsing."
                            },
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    uiState.viewMode == BookViewMode.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        gridItems(uiState.books, key = { it.key }) { book ->
                            LibraryGridCard(
                                book = book,
                                progress = book.progressFrom(overlays),
                                downloaded = book.downloadedIn(overlays),
                                onClick = { onTapCover(book) },
                                actions = actionsFor(book),
                            )
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(uiState.books, key = { it.key }) { book ->
                            LibraryRow(
                                book = book,
                                progress = book.progressFrom(overlays),
                                downloaded = book.downloadedIn(overlays),
                                onClick = { onTapCover(book) },
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
private fun LibraryRow(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: LibraryItemActions,
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
        LibraryMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridCard(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: LibraryItemActions,
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
        LibraryMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@Composable
private fun LibraryMenu(expanded: Boolean, onDismiss: () -> Unit, actions: LibraryItemActions) {
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
