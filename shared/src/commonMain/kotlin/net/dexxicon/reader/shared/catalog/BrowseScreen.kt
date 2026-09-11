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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.shared.di.AppContainer

/**
 * The merged, de-duplicated view across every configured server (issue #82) — a "Continue
 * reading" shelf followed by a searchable, sortable grid. See [BrowseState]'s doc comment for
 * what's deliberately left out of this pass (shelf filtering, pagination).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { BrowseState(container.catalogRepository, scope) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.onDeck.isNotEmpty()) {
                Text(
                    "Continue reading",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.onDeck, key = { "${it.serverId}:${it.id}" }) { book ->
                        OnDeckCard(
                            book = book,
                            imageLoader = container.imageLoader,
                            onClick = { onOpenBook(book.serverId, book.id) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.queryDraft,
                    onValueChange = { state.queryDraft = it },
                    label = { Text("Search everything") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.search() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = state::search) { Text("Go") }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = listOf(BookSort.RECENT to "Recent", BookSort.TITLE to "Title", BookSort.SERIES to "Series")
                for ((value, label) in options) {
                    FilterChip(
                        selected = state.sort == value,
                        onClick = { state.onSortChange(value) },
                        label = { Text(label) },
                    )
                }
            }

            AggregatedGrid(state = state, imageLoader = container.imageLoader, onOpenBook = onOpenBook)
        }
    }
}

@Composable
private fun AggregatedGrid(
    state: BrowseState,
    imageLoader: ImageLoader,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
) {
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
            Text("No books match this search.", style = MaterialTheme.typography.bodyMedium)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.books, key = { it.key }) { book ->
                AggregatedBookCard(
                    book = book,
                    imageLoader = imageLoader,
                    onClick = { onOpenBook(book.primary.serverId, book.primary.bookId) },
                )
            }
        }
    }
}

@Composable
private fun AggregatedBookCard(book: AggregatedBook, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = book.title,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
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

@Composable
private fun OnDeckCard(book: BookSummary, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column(Modifier.width(100.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = book.title,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(book.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
    }
}
