package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.shared.di.AppContainer

/**
 * A server's book catalog — the first page only, no shelves/search/sort yet (issue #78 keeps
 * this to a read-only MVP: browse + detail, no download/mark-read/reading-status actions,
 * which still need `BookActions`, genuinely Android-only). Covers load through
 * [AppContainer.imageLoader] — the same authenticated Ktor client every other call uses, so
 * covers on a private catalog load exactly like everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    container: AppContainer,
    serverId: String,
    onBack: () -> Unit,
    onOpenBook: (bookId: String) -> Unit,
) {
    var books by remember(serverId) { mutableStateOf<List<BookSummary>?>(null) }
    var error by remember(serverId) { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        when (val result = container.catalogRepository.books(serverId, null, null, BookSort.RECENT, page = 0)) {
            is Outcome.Success -> books = result.value.books
            is Outcome.Failure -> error = result.error.message ?: "Couldn't load this server's catalog"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        val currentError = error
        val currentBooks = books
        when {
            currentError != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(currentError, color = MaterialTheme.colorScheme.error)
            }
            currentBooks == null -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            currentBooks.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text("No books on this server yet.", style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(currentBooks, key = { it.id }) { book ->
                    BookCard(book = book, imageLoader = container.imageLoader, onClick = { onOpenBook(book.id) })
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
