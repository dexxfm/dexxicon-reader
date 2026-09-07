package net.dexxicon.reader.feature.catalog

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.DownloadStatus

/** What the long-press menu on a catalog card needs. */
private data class CatalogItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onBack: () -> Unit,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = if (state.viewMode == BookViewMode.GRID) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } ?: return@derivedStateOf false
            state.books.isNotEmpty() && last >= state.books.size - 8
        }
    }
    androidx.compose.runtime.LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    fun actionsFor(book: BookSummary): CatalogItemActions {
        val status = if (book.id in overlays.downloaded) DownloadStatus.DONE else null
        return CatalogItemActions(
            downloadStatus = status,
            onMarkRead = { viewModel.markRead(book.serverId, book.id) },
            onMarkUnread = { viewModel.markUnread(book.serverId, book.id) },
            onDetails = { onOpenBook(book.serverId, book.id) },
            onDownloadOrRemove = { viewModel.downloadOrRemove(book.serverId, book.id, status) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.serverName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(state.query, viewModel::onQueryChange)
            ShelfRow(
                shelves = state.shelves.map { it.id to it.title },
                selectedShelfId = state.selectedShelfId,
                sort = state.sort,
                onShelf = viewModel::onShelfSelected,
                onSort = viewModel::onSortSelected,
            )
            Row(
                Modifier.fillMaxWidth().padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContentFilterChips(state.filter, viewModel::onFilterSelected, Modifier.weight(1f))
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
                    state.books.isEmpty() -> CenterBox { Text("Nothing here yet") }
                    state.viewMode == BookViewMode.GRID -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        gridItems(state.books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                progress = overlays.progress[book.id],
                                downloaded = book.id in overlays.downloaded,
                                onClick = { onOpenBook(book.serverId, book.id) },
                                actions = actionsFor(book),
                            )
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            BookRow(
                                book = book,
                                progress = overlays.progress[book.id],
                                downloaded = book.id in overlays.downloaded,
                                onClick = { onOpenBook(book.serverId, book.id) },
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

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search titles, authors…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun ShelfRow(
    shelves: List<Pair<String, String>>,
    selectedShelfId: String?,
    sort: BookSort,
    onShelf: (String?) -> Unit,
    onSort: (BookSort) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedShelfId == null,
                onClick = { onShelf(null) },
                label = { Text("All") },
            )
        }
        rowItems(shelves) { (id, title) ->
            FilterChip(
                selected = selectedShelfId == id,
                onClick = { onShelf(id) },
                label = { Text(title) },
            )
        }
        item {
            val next = BookSort.entries[(sort.ordinal + 1) % BookSort.entries.size]
            FilterChip(
                selected = false,
                onClick = { onSort(next) },
                label = { Text("Sort: ${sort.name.lowercase()}") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookSummary,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: CatalogItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
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
            if (book.authorLine.isNotBlank()) {
                Text(
                    book.authorLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CatalogMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: BookSummary,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: CatalogItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val subtitle = buildString {
        if (book.authorLine.isNotBlank()) append(book.authorLine)
        append(if (isEmpty()) "" else " · ")
        append(book.format.name.lowercase())
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
        CatalogMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@Composable
private fun CatalogMenu(expanded: Boolean, onDismiss: () -> Unit, actions: CatalogItemActions) {
    BookContextMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        downloadStatus = actions.downloadStatus,
        onMarkRead = actions.onMarkRead,
        onMarkUnread = actions.onMarkUnread,
        onDetails = actions.onDetails,
        onDownloadOrRemove = actions.onDownloadOrRemove,
    )
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
