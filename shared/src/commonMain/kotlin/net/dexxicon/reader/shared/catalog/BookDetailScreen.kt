package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import coil3.compose.AsyncImage
import io.ktor.http.Url
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Read-only-ish book detail — title, authors, format, description, a reading status the user
 * can change (issue #84), and (issue #99) a "Read" action that hands off to a native reader —
 * see [OnOpenReader]'s doc comment for why that's a platform-supplied callback rather than a
 * screen this file owns. `BookActions`'s download/remove stays out of scope — genuinely
 * Android-only (WorkManager), unrelated to reading itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    container: AppContainer,
    serverId: String,
    bookId: String,
    onBack: () -> Unit,
    onOpenReader: OnOpenReader,
) {
    var detail by remember(serverId, bookId) { mutableStateOf<BookDetail?>(null) }
    var error by remember(serverId, bookId) { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId, bookId) {
        when (val result = container.catalogRepository.detail(serverId, bookId)) {
            is Outcome.Success -> detail = result.value
            is Outcome.Failure -> error = result.error.message ?: "Couldn't load this book"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.summary?.title ?: "") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        val currentError = error
        val currentDetail = detail
        when {
            currentError != null -> Text(
                currentError,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            )
            currentDetail == null -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = currentDetail.summary.coverUrl,
                        contentDescription = currentDetail.summary.title,
                        imageLoader = container.imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(2f / 3f),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(currentDetail.summary.title, style = MaterialTheme.typography.titleLarge)
                        if (currentDetail.summary.authorLine.isNotBlank()) {
                            Text(
                                currentDetail.summary.authorLine,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            currentDetail.summary.format.name.lowercase()
                                .replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ReadingStatus.entries.toList()) { status ->
                        FilterChip(
                            selected = status == currentDetail.readingStatus,
                            onClick = {
                                container.readingStatusActions.setReadingStatus(serverId, bookId, status)
                                detail = currentDetail.copy(readingStatus = status)
                            },
                            label = { Text(status.label) },
                        )
                    }
                }

                // issue #99 — the acquisition's URL + a freshly-resolved auth header are
                // plain data by the time they leave :shared; the platform host never needs
                // its own path back into the auth/network layer just to open a book.
                currentDetail.primaryAcquisition?.let { acquisition ->
                    Button(onClick = {
                        val header = container.authHeaderProvider.authHeader(Url(acquisition.href))
                        onOpenReader(serverId, bookId, currentDetail.summary.format, acquisition.href, header)
                    }) { Text("Read") }
                }

                currentDetail.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
