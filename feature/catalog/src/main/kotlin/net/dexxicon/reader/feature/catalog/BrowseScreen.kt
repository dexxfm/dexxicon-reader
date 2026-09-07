package net.dexxicon.reader.feature.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
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
            SortRow(state.sort, viewModel::onSortSelected)

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
                            if (state.query.isNotBlank()) {
                                "No books match “${state.query}”."
                            } else {
                                "Add a server in Settings to start browsing."
                            },
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.books, key = { it.key }) { book ->
                            BrowseRow(
                                book = book,
                                progress = book.copies
                                    .mapNotNull { overlays.progress["${it.serverId}::${it.bookId}"] }
                                    .maxOrNull(),
                                downloaded = book.copies
                                    .any { "${it.serverId}::${it.bookId}" in overlays.downloaded },
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

@Composable
private fun SortRow(sort: BookSort, onSort: (BookSort) -> Unit) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        val next = BookSort.entries[(sort.ordinal + 1) % BookSort.entries.size]
        FilterChip(
            selected = false,
            onClick = { onSort(next) },
            label = { Text("Sort: ${sort.name.lowercase()}") },
        )
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
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
