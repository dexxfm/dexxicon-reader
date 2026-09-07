package net.dexxicon.reader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onContinue: (serverId: String, bookId: String, format: ContentFormat) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { padding ->
        val empty = state.downloads.isEmpty() &&
            state.continueReading.isEmpty() &&
            state.continueListening.isEmpty()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                empty -> Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    Alignment.Center,
                ) {
                    Text(
                        "Books you read or make available offline show up here.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    continueShelf("Continue reading", state.continueReading, onContinue)
                    continueShelf("Continue listening", state.continueListening, onContinue)

                    if (state.downloads.isNotEmpty()) {
                        fullWidthItem { SectionHeader("Downloaded") }
                        items(state.downloads, key = { it.key }) { download ->
                            DownloadCard(
                                download = download,
                                readingProgress = state.downloadProgress[download.key],
                            ) { onOpenBook(download.serverId, download.bookId) }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidthItem(
    content: @Composable () -> Unit,
) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.continueShelf(
    title: String,
    items: List<ContinueItem>,
    onContinue: (String, String, ContentFormat) -> Unit,
) {
    if (items.isEmpty()) return
    fullWidthItem { SectionHeader(title) }
    fullWidthItem {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.serverId}:${it.bookId}" }) { entry ->
                ContinueCard(entry) { onContinue(entry.serverId, entry.bookId, entry.format) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ContinueCard(entry: ContinueItem, onClick: () -> Unit) {
    val pct = (entry.percent * 100).toInt()
    Column(
        Modifier
            .width(112.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.title}, $pct% ${
                    if (entry.format == ContentFormat.AUDIOBOOK) "listened" else "read"
                }"
            },
    ) {
        CoverImage(
            coverUrl = entry.coverUrl,
            contentDescription = null,
            progress = entry.percent,
        )
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "$pct%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadCard(download: Download, readingProgress: Float?, onClick: () -> Unit) {
    val done = download.status == DownloadStatus.DONE
    val pct = readingProgress?.let { (it * 100).toInt() }
    val label = buildString {
        append(download.title)
        if (download.authorLine.isNotBlank()) append(", ${download.authorLine}")
        when (download.status) {
            DownloadStatus.DONE -> {
                append(", downloaded")
                if (pct != null) append(", $pct% read")
            }
            DownloadStatus.FAILED -> append(", download failed")
            else -> append(", downloading")
        }
    }
    Column(
        Modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Box(Modifier.fillMaxWidth()) {
            CoverImage(
                coverUrl = download.coverUrl,
                contentDescription = null,
                progress = if (done) readingProgress else null,
                downloaded = done,
            )
            when (download.status) {
                DownloadStatus.DONE -> {}
                DownloadStatus.FAILED -> Text(
                    "Failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(2.dp),
                    textAlign = TextAlign.Center,
                )
                else -> {
                    val fraction = download.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Text(
            download.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (download.authorLine.isNotBlank()) {
            Text(
                download.authorLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
