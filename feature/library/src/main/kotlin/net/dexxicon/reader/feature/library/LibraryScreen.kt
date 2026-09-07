package net.dexxicon.reader.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.component.ViewModeToggle
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus

/** What the long-press menu on a Library card needs. */
private data class LibraryItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onContinue: (serverId: String, bookId: String, format: ContentFormat) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    fun continueActions(entry: ContinueItem) = LibraryItemActions(
        downloadStatus = null,
        onMarkRead = { viewModel.markRead(entry.serverId, entry.bookId) },
        onMarkUnread = { viewModel.markUnread(entry.serverId, entry.bookId) },
        onDetails = { onOpenBook(entry.serverId, entry.bookId) },
        onDownloadOrRemove = { viewModel.downloadOrRemove(entry.serverId, entry.bookId, null) },
    )

    fun downloadActions(download: Download): LibraryItemActions {
        val status = download.status
        return LibraryItemActions(
            downloadStatus = status,
            onMarkRead = { viewModel.markRead(download.serverId, download.bookId) },
            onMarkUnread = { viewModel.markUnread(download.serverId, download.bookId) },
            onDetails = { onOpenBook(download.serverId, download.bookId) },
            onDownloadOrRemove = { viewModel.downloadOrRemove(download.serverId, download.bookId, status) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = { ViewModeToggle(state.viewMode, viewModel::toggleViewMode) },
            )
        },
    ) { padding ->
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
                empty -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
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
                    continueShelf("Continue reading", state.continueReading, onContinue, ::continueActions)
                    continueShelf("Continue listening", state.continueListening, onContinue, ::continueActions)

                    if (state.downloads.isNotEmpty()) {
                        fullWidthItem { SectionHeader("Downloaded") }
                        if (state.viewMode == BookViewMode.LIST) {
                            items(
                                state.downloads,
                                span = { GridItemSpan(maxLineSpan) },
                                key = { it.key },
                            ) { download ->
                                DownloadRow(
                                    download = download,
                                    readingProgress = state.downloadProgress[download.key],
                                    onClick = { onOpenBook(download.serverId, download.bookId) },
                                    actions = downloadActions(download),
                                )
                            }
                        } else {
                            items(state.downloads, key = { it.key }) { download ->
                                DownloadCard(
                                    download = download,
                                    readingProgress = state.downloadProgress[download.key],
                                    onClick = { onOpenBook(download.serverId, download.bookId) },
                                    actions = downloadActions(download),
                                )
                            }
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
    actionsFor: (ContinueItem) -> LibraryItemActions,
) {
    if (items.isEmpty()) return
    fullWidthItem { SectionHeader(title) }
    fullWidthItem {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.serverId}:${it.bookId}" }) { entry ->
                ContinueCard(
                    entry = entry,
                    onClick = { onContinue(entry.serverId, entry.bookId, entry.format) },
                    actions = actionsFor(entry),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: ContinueItem, onClick: () -> Unit, actions: LibraryItemActions) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = (entry.percent * 100).toInt()
    Box(Modifier.width(112.dp)) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
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
                format = entry.format,
            )
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text("$pct%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LibraryMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCard(
    download: Download,
    readingProgress: Float?,
    onClick: () -> Unit,
    actions: LibraryItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
    Box {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) { contentDescription = label },
        ) {
            Box(Modifier.fillMaxWidth()) {
                CoverImage(
                    coverUrl = download.coverUrl,
                    contentDescription = null,
                    progress = if (done) readingProgress else null,
                    downloaded = done,
                    format = download.format,
                )
                DownloadStatusOverlay(download)
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
        LibraryMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    download: Download,
    readingProgress: Float?,
    onClick: () -> Unit,
    actions: LibraryItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = download.status == DownloadStatus.DONE
    val pct = readingProgress?.let { (it * 100).toInt() }
    val subtitle = buildString {
        if (download.authorLine.isNotBlank()) append(download.authorLine)
        append(if (isEmpty()) "" else " · ")
        append(download.format.name.lowercase())
        when (download.status) {
            DownloadStatus.DONE -> if (pct != null) append(" · $pct% read")
            DownloadStatus.FAILED -> append(" · failed")
            else -> append(" · downloading")
        }
    }
    Box {
        ListItem(
            headlineContent = { Text(download.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Box(Modifier.width(44.dp)) {
                    CoverImage(
                        coverUrl = download.coverUrl,
                        contentDescription = null,
                        progress = if (done) readingProgress else null,
                        downloaded = done,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        )
        LibraryMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.DownloadStatusOverlay(download: Download) {
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
                LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LibraryMenu(expanded: Boolean, onDismiss: () -> Unit, actions: LibraryItemActions) {
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
