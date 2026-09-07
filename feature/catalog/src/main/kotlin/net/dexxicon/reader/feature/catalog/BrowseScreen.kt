package net.dexxicon.reader.feature.catalog

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort

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
            val last = if (state.viewMode == BrowseViewMode.GRID) {
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

    Scaffold(topBar = { TopAppBar(title = { Text("Browse") }) }) { padding ->
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

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(BrowseFilter.entries) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.onFilterSelected(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }

            ControlsRow(
                sort = state.sort,
                onSort = viewModel::onSortSelected,
                viewMode = state.viewMode,
                onToggleView = viewModel::toggleViewMode,
            )

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
                                state.filter != BrowseFilter.ALL -> "No ${state.filter.label.lowercase()} here."
                                else -> "Add a server in Settings to start browsing."
                            },
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    state.viewMode == BrowseViewMode.GRID -> LazyVerticalGrid(
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

@Composable
private fun ControlsRow(
    sort: BookSort,
    onSort: (BookSort) -> Unit,
    viewMode: BrowseViewMode,
    onToggleView: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val next = BookSort.entries[(sort.ordinal + 1) % BookSort.entries.size]
        FilterChip(
            selected = false,
            onClick = { onSort(next) },
            label = { Text("Sort: ${sort.name.lowercase()}") },
        )
        IconButton(onClick = onToggleView) {
            if (viewMode == BrowseViewMode.LIST) {
                Icon(Icons.Filled.GridView, contentDescription = "Grid view")
            } else {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "List view")
            }
        }
    }
}

@Composable
private fun BrowseRow(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
) {
    val pct = progress?.let { (it * 100).toInt() }
    val libraries = book.copies.size
    val subtitle = buildString {
        if (book.authorLine.isNotBlank()) append(book.authorLine)
        append(if (isEmpty()) "" else " · ")
        append(book.format.name.lowercase())
        if (libraries > 1) append(" · On $libraries libraries")
        if (pct != null) append(" · $pct% read")
    }
    ListItem(
        headlineContent = {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Box(Modifier.width(44.dp)) {
                // No format badge here — it's too cramped at 44dp and the subtitle already
                // names the format.
                CoverImage(
                    coverUrl = book.coverUrl,
                    contentDescription = null,
                    progress = progress,
                    downloaded = downloaded,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${book.title}, ${book.authorLine}, $subtitle"
            },
    )
}

@Composable
private fun BrowseGridCard(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
) {
    val pct = progress?.let { (it * 100).toInt() }
    val secondary = when {
        book.copies.size > 1 -> "On ${book.copies.size} libraries"
        else -> book.authorLine
    }
    val label = buildString {
        append(book.title)
        if (book.authorLine.isNotBlank()) append(", ${book.authorLine}")
        if (pct != null) append(", $pct% read")
        if (downloaded) append(", downloaded")
    }
    Column(
        Modifier
            .clickable(onClick = onClick)
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
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
