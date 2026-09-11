package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Read-only book detail — title, authors, format, description. No download, no mark-read, no
 * reading-status change and no actual reading: `BookActions` and the reader modules
 * (Readium/PDFium/Media3) are both out of scope for issue #78, the former genuinely
 * Android-only today, the latter a separate, already-deferred decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    container: AppContainer,
    serverId: String,
    bookId: String,
    onBack: () -> Unit,
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
                currentDetail.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
