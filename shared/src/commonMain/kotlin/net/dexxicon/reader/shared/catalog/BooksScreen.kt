package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.shared.di.AppContainer

/**
 * A server's book catalog — shelf filter, search, sort, and "Load more" pagination (issue
 * #80), all on top of [net.dexxicon.reader.core.data.CatalogRepository]'s existing support
 * for them (see [BooksState]). Still read-only: no download/mark-read/reading-status actions
 * (`BookActions`, genuinely Android-only), no actual reading (the reader modules, a separate
 * deferred decision). Covers load through [AppContainer.imageLoader] — the same authenticated
 * Ktor client every other call uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    container: AppContainer,
    serverId: String,
    onBack: () -> Unit,
    onOpenBook: (bookId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(serverId) { BooksState(container.catalogRepository, serverId, scope) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.queryDraft,
                    onValueChange = { state.queryDraft = it },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.search() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = state::search) { Text("Go") }
            }

            SortRow(sort = state.sort, onSortChange = state::onSortChange)

            if (state.shelves.isNotEmpty()) {
                ShelfRow(
                    shelves = state.shelves,
                    selectedShelfId = state.selectedShelfId,
                    onShelfSelected = state::onShelfSelected,
                )
            }

            BooksGrid(state = state, imageLoader = container.imageLoader, onOpenBook = onOpenBook)
        }
    }
}

@Composable
private fun SortRow(sort: BookSort, onSortChange: (BookSort) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val options = listOf(BookSort.RECENT to "Recent", BookSort.TITLE to "Title", BookSort.SERIES to "Series")
        for ((value, label) in options) {
            FilterChip(
                selected = sort == value,
                onClick = { onSortChange(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ShelfRow(
    shelves: List<CatalogShelf>,
    selectedShelfId: String?,
    onShelfSelected: (String?) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedShelfId == null,
                onClick = { onShelfSelected(null) },
                label = { Text("All") },
            )
        }
        items(shelves, key = { it.id }) { shelf ->
            FilterChip(
                selected = shelf.id == selectedShelfId,
                onClick = { onShelfSelected(shelf.id) },
                label = { Text(shelf.bookCount?.let { "${shelf.title} ($it)" } ?: shelf.title) },
            )
        }
    }
}

@Composable
private fun BooksGrid(state: BooksState, imageLoader: ImageLoader, onOpenBook: (String) -> Unit) {
    val currentError = state.error
    when {
        currentError != null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(currentError, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = state::retry) { Text("Retry") }
        }
        state.loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        state.books.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Text("No books match this filter.", style = MaterialTheme.typography.bodyMedium)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.books, key = { it.id }) { book ->
                BookCard(book = book, imageLoader = imageLoader, onClick = { onOpenBook(book.id) })
            }
            if (state.hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.padding(8.dp))
                        } else {
                            TextButton(onClick = state::loadMore) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: BookSummary,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = book.title,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (book.authorLine.isNotBlank()) {
            Text(
                book.authorLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
