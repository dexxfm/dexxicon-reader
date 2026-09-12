package net.dexxicon.reader.shared.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.data.sync.ServerSyncFailure
import net.dexxicon.reader.core.data.sync.SyncFailureReason
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader

/** What the long-press menu on a Home card needs. */
private data class HomeItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onSetStatus: (ReadingStatus) -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

/**
 * Phase 4 Stage C (issue #130) — the one Home composable, ported as-is from native's
 * `feature/library/LibraryScreen.kt` (same shelves, same cards, same context-menu wiring), with
 * two changes: it reads from [HomeState] instead of a Hilt `LibraryViewModel`, and a tap on a
 * Continue reading/listening card calls [HomeState.continueReading] (which resolves the reader
 * launch itself) instead of bubbling a raw `(serverId, bookId, format)` tuple back up to a
 * platform-specific `onContinue` — one fewer thing each thin wrapper needs to adapt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeState,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onOpenReader: OnOpenReader,
    modifier: Modifier = Modifier,
) {
    val uiState by state.uiState.collectAsState()

    fun continueActions(entry: ContinueItem) = HomeItemActions(
        downloadStatus = null,
        onMarkRead = { state.markRead(entry.serverId, entry.bookId) },
        onMarkUnread = { state.markUnread(entry.serverId, entry.bookId) },
        onSetStatus = { state.setReadingStatus(entry.serverId, entry.bookId, it) },
        onDetails = { onOpenBook(entry.serverId, entry.bookId) },
        onDownloadOrRemove = { state.downloadOrRemove(entry.serverId, entry.bookId, null) },
    )

    fun onDeckActions(entry: OnDeckItem) = HomeItemActions(
        downloadStatus = null,
        onMarkRead = { state.markRead(entry.serverId, entry.bookId) },
        onMarkUnread = { state.markUnread(entry.serverId, entry.bookId) },
        onSetStatus = { state.setReadingStatus(entry.serverId, entry.bookId, it) },
        onDetails = { onOpenBook(entry.serverId, entry.bookId) },
        onDownloadOrRemove = { state.downloadOrRemove(entry.serverId, entry.bookId, null) },
    )

    fun downloadActions(download: Download): HomeItemActions {
        val status = download.status
        return HomeItemActions(
            downloadStatus = status,
            onMarkRead = { state.markRead(download.serverId, download.bookId) },
            onMarkUnread = { state.markUnread(download.serverId, download.bookId) },
            onSetStatus = { state.setReadingStatus(download.serverId, download.bookId, it) },
            onDetails = { onOpenBook(download.serverId, download.bookId) },
            onDownloadOrRemove = { state.downloadOrRemove(download.serverId, download.bookId, status) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Home") })
        },
        // The app shell already accounts for the bottom nav / system inset; without this
        // the Scaffold reserves it again and leaves a dead strip under the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val empty = uiState.downloads.isEmpty() &&
            uiState.onDeck.isEmpty() &&
            uiState.continueReading.isEmpty() &&
            uiState.continueListening.isEmpty()
        Column(Modifier.fillMaxSize().padding(padding)) {
            PullToRefreshBox(
                isRefreshing = uiState.refreshing,
                onRefresh = { state.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    uiState.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    empty -> Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(32.dp),
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
                        continueShelf("Continue reading", uiState.continueReading, state, onOpenReader, ::continueActions)
                        continueShelf("Continue listening", uiState.continueListening, state, onOpenReader, ::continueActions)
                        onDeckShelf(uiState.onDeck, onOpenBook, ::onDeckActions)

                        if (uiState.downloads.isNotEmpty()) {
                            fullWidthItem { SectionHeader("Downloaded") }
                            items(uiState.downloads, key = { it.key }) { download ->
                                DownloadCard(
                                    download = download,
                                    readingProgress = uiState.downloadProgress[download.key],
                                    onClick = { onOpenBook(download.serverId, download.bookId) },
                                    actions = downloadActions(download),
                                )
                            }
                        }
                    }
                }
            }
            SyncStatusBar(
                lastSyncedAt = uiState.lastSyncedAt,
                failures = uiState.syncFailures,
                refreshing = uiState.refreshing,
                visible = !uiState.loading &&
                    (uiState.refreshing || uiState.lastSyncedAt != null || uiState.syncFailures.isNotEmpty()),
                onRetry = { state.refresh() },
            )
        }
    }
}

@Composable
private fun SyncStatusBar(
    lastSyncedAt: Long?,
    failures: List<ServerSyncFailure>,
    refreshing: Boolean,
    visible: Boolean,
    onRetry: () -> Unit,
) {
    if (!visible) return
    val hasFailure = failures.isNotEmpty() && !refreshing
    val container = if (hasFailure) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (hasFailure) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container) {
        Column {
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        refreshing -> "Updating…"
                        hasFailure -> syncFailureText(failures)
                        lastSyncedAt != null -> "Updated ${relativeTime(lastSyncedAt)}"
                        else -> "Not synced yet"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasFailure) {
                    Text(
                        "Retry",
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable(onClick = onRetry)
                            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

private fun syncFailureText(failures: List<ServerSyncFailure>): String {
    if (failures.size == 1) {
        val f = failures.first()
        return when (f.reason) {
            SyncFailureReason.OFFLINE -> "Couldn't reach ${f.serverName}"
            SyncFailureReason.SIGN_IN_REQUIRED -> "${f.serverName} needs you to sign in again"
            SyncFailureReason.SERVER_ERROR -> "${f.serverName} didn't respond properly"
        }
    }
    val names = failures.map { it.serverName }
    val joined = names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "Couldn't update $joined"
}

private fun LazyGridScope.fullWidthItem(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.onDeckShelf(
    items: List<OnDeckItem>,
    onOpenBook: (String, String) -> Unit,
    actionsFor: (OnDeckItem) -> HomeItemActions,
) {
    if (items.isEmpty()) return
    fullWidthItem { SectionHeader("On Deck") }
    fullWidthItem {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.serverId}:${it.bookId}" }) { entry ->
                OnDeckCard(
                    entry = entry,
                    onClick = { onOpenBook(entry.serverId, entry.bookId) },
                    actions = actionsFor(entry),
                )
            }
        }
    }
}

private fun LazyGridScope.continueShelf(
    title: String,
    items: List<ContinueItem>,
    state: HomeState,
    onOpenReader: OnOpenReader,
    actionsFor: (ContinueItem) -> HomeItemActions,
) {
    if (items.isEmpty()) return
    fullWidthItem { SectionHeader(title) }
    fullWidthItem {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.serverId}:${it.bookId}" }) { entry ->
                ContinueCard(
                    entry = entry,
                    onClick = { state.continueReading(entry, onOpenReader) },
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
private fun OnDeckCard(entry: OnDeckItem, onClick: () -> Unit, actions: HomeItemActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.width(112.dp)) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(entry.title)
                        entry.author?.let { append(", ").append(it) }
                        append(", want to read")
                    }
                },
        ) {
            CoverImage(
                coverUrl = entry.coverUrl,
                contentDescription = null,
                format = entry.format,
            )
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            entry.author?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HomeMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: ContinueItem, onClick: () -> Unit, actions: HomeItemActions) {
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
        HomeMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCard(
    download: Download,
    readingProgress: Float?,
    onClick: () -> Unit,
    actions: HomeItemActions,
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
        HomeMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@Composable
private fun BoxScope.DownloadStatusOverlay(download: Download) {
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
private fun HomeMenu(expanded: Boolean, onDismiss: () -> Unit, actions: HomeItemActions) {
    BookContextMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        downloadStatus = actions.downloadStatus,
        currentStatus = null,
        onMarkRead = actions.onMarkRead,
        onMarkUnread = actions.onMarkUnread,
        onSetStatus = actions.onSetStatus,
        onDetails = actions.onDetails,
        onDownloadOrRemove = actions.onDownloadOrRemove,
    )
}
