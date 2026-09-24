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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.data.sync.ServerSyncFailure
import net.dexxicon.reader.core.data.sync.SyncFailureReason
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.EdgeFadeRow
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.nav.FloatingNavClearance
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.HomeSection
import net.dexxicon.reader.core.model.HomeShelf
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
    /** issue #251 — Continue shelves only. */
    val onRemoveFromContinue: (() -> Unit)? = null,
    /** issue #298 — the book comes from an OPDS catalog. */
    val catalogOnly: Boolean = false,
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
    val layout by state.layout.collectAsState()
    val pinned by state.pinnedShelves.collectAsState()
    val catalogServers by state.catalogServerIds.collectAsState()

    fun continueActions(entry: ContinueItem) = HomeItemActions(
        downloadStatus = entry.downloadStatus,
        onMarkRead = { state.markRead(entry.serverId, entry.bookId) },
        onMarkUnread = { state.markUnread(entry.serverId, entry.bookId) },
        onSetStatus = { state.setReadingStatus(entry.serverId, entry.bookId, it) },
        onDetails = { onOpenBook(entry.serverId, entry.bookId) },
        onDownloadOrRemove = { state.downloadOrRemove(entry.serverId, entry.bookId, entry.downloadStatus) },
        onRemoveFromContinue = { state.hideFromContinue(entry) },
        catalogOnly = entry.serverId in catalogServers,
    )

    fun onDeckActions(entry: OnDeckItem) = HomeItemActions(
        downloadStatus = entry.downloadStatus,
        onMarkRead = { state.markRead(entry.serverId, entry.bookId) },
        onMarkUnread = { state.markUnread(entry.serverId, entry.bookId) },
        onSetStatus = { state.setReadingStatus(entry.serverId, entry.bookId, it) },
        onDetails = { onOpenBook(entry.serverId, entry.bookId) },
        onDownloadOrRemove = { state.downloadOrRemove(entry.serverId, entry.bookId, entry.downloadStatus) },
        catalogOnly = entry.serverId in catalogServers,
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

    // issue #251 — one-off notes (a stale book removed, a tap that couldn't open, Undo).
    val snackbarHostState = remember { SnackbarHostState() }
    val message by state.message.collectAsState()
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel == null)
        if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        // Cleared only once it's been shown: clearing first changes this effect's key and
        // cancels the snackbar before it ever appears. A newer message replaces this one.
        state.messageShown(m)
    }

    val syncVisible = !uiState.loading &&
        (uiState.refreshing || uiState.lastSyncedAt != null || uiState.syncFailures.isNotEmpty())
    val syncHasFailure = uiState.syncFailures.isNotEmpty() && !uiState.refreshing

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            // Lifted clear of the floating nav, which overlays the bottom of the screen.
            SnackbarHost(snackbarHostState, Modifier.padding(bottom = FloatingNavClearance))
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Home")
                        // issue #132 — this used to be its own full-width bar docked above the
                        // bottom nav; moved into the app bar once the nav started floating over
                        // content instead of reserving space for a strip like this to sit in.
                        if (syncVisible) {
                            val color = if (syncHasFailure) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = when {
                                    uiState.refreshing -> "Updating…"
                                    syncHasFailure -> syncFailureText(uiState.syncFailures) + " · Retry"
                                    uiState.lastSyncedAt != null -> "Updated ${relativeTime(uiState.lastSyncedAt!!)}"
                                    else -> "Not synced yet"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (syncHasFailure) {
                                    Modifier.clickable { state.refresh() }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                },
            )
        },
        // The app shell already accounts for the top status-bar inset; without this the
        // Scaffold reserves it again and leaves a dead strip above the title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // issue #255 — only the shelves the user hasn't hidden count; hiding the only
        // non-empty one should land on the empty state, not a blank screen.
        val empty = layout.visible.none { shelf ->
            when (shelf) {
                is HomeShelf.Pinned -> pinned[shelf.key].orEmpty().isNotEmpty()
                is HomeShelf.Section -> when (shelf.section) {
                    HomeSection.CONTINUE_READING -> uiState.continueReading.isNotEmpty()
                    HomeSection.CONTINUE_LISTENING -> uiState.continueListening.isNotEmpty()
                    HomeSection.ON_DECK -> uiState.onDeck.isNotEmpty()
                    HomeSection.DOWNLOADED -> uiState.downloads.isNotEmpty()
                }
            }
        }
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
                            if (layout.visible.isEmpty()) {
                                "Every Home shelf is hidden. Turn them back on in Settings › Arrange Home."
                            } else {
                                "Books you read or make available offline show up here."
                            },
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.fillMaxSize(),
                        // issue #132 — bottom padding clears the floating nav (and mini-player,
                        // when showing) now that it overlays content instead of reserving its
                        // own Scaffold space.
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = FloatingNavClearance,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // issues #255/#254 — shelves in the user's order, hidden ones skipped,
                        // pinned groups mixed in wherever they were dragged to.
                        layout.visible.forEach { shelf ->
                            when (shelf) {
                                is HomeShelf.Pinned -> onDeckShelf(
                                    shelf.title, pinned[shelf.key].orEmpty(), onOpenBook, ::onDeckActions,
                                )
                                is HomeShelf.Section -> when (val section = shelf.section) {
                                    HomeSection.CONTINUE_READING -> continueShelf(
                                        section.title, uiState.continueReading, state, onOpenReader, ::continueActions,
                                    )
                                    HomeSection.CONTINUE_LISTENING -> continueShelf(
                                        section.title, uiState.continueListening, state, onOpenReader, ::continueActions,
                                    )
                                    HomeSection.ON_DECK -> onDeckShelf(
                                        section.title, uiState.onDeck, onOpenBook, ::onDeckActions, wantToRead = true,
                                    )
                                    HomeSection.DOWNLOADED -> downloadedShelf(uiState, onOpenBook, ::downloadActions)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A shelf's heading on Home — also how Settings › Arrange Home lists it. */
val HomeShelf.title: String
    get() = when (this) {
        is HomeShelf.Section -> section.title
        is HomeShelf.Pinned -> group.name
    }

/** Home's shelf headings — also how Settings › Arrange Home lists them. */
val HomeSection.title: String
    get() = when (this) {
        HomeSection.CONTINUE_READING -> "Continue reading"
        HomeSection.CONTINUE_LISTENING -> "Continue listening"
        HomeSection.ON_DECK -> "On Deck"
        HomeSection.DOWNLOADED -> "Downloaded"
    }

private fun LazyGridScope.downloadedShelf(
    uiState: HomeUiState,
    onOpenBook: (String, String) -> Unit,
    actionsFor: (Download) -> HomeItemActions,
) {
    if (uiState.downloads.isEmpty()) return
    fullWidthItem { SectionHeader(HomeSection.DOWNLOADED.title) }
    items(uiState.downloads, key = { it.key }) { download ->
        DownloadCard(
            download = download,
            readingProgress = uiState.downloadProgress[download.key],
            seriesIndex = uiState.downloadSeriesIndex[download.key],
            onClick = { onOpenBook(download.serverId, download.bookId) },
            actions = actionsFor(download),
        )
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

/** A row of books that open Book Detail when tapped — On Deck, and every pinned group's
 * shelf (issue #254). */
private fun LazyGridScope.onDeckShelf(
    title: String,
    items: List<OnDeckItem>,
    onOpenBook: (String, String) -> Unit,
    actionsFor: (OnDeckItem) -> HomeItemActions,
    /** On Deck's books are ones the user flagged; a pinned shelf's books aren't. */
    wantToRead: Boolean = false,
) {
    if (items.isEmpty()) return
    fullWidthItem { SectionHeader(title) }
    fullWidthItem {
        val listState = rememberLazyListState()
        EdgeFadeRow(listState) {
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { "${it.serverId}:${it.bookId}" }) { entry ->
                    OnDeckCard(
                        entry = entry,
                        onClick = { onOpenBook(entry.serverId, entry.bookId) },
                        actions = actionsFor(entry),
                        wantToRead = wantToRead,
                    )
                }
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
        val listState = rememberLazyListState()
        EdgeFadeRow(listState) {
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnDeckCard(entry: OnDeckItem, onClick: () -> Unit, actions: HomeItemActions, wantToRead: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.width(112.dp)) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(entry.title)
                        entry.author?.let { append(", ").append(it) }
                        if (wantToRead) append(", want to read")
                        if (entry.downloadStatus == DownloadStatus.DONE) append(", downloaded")
                    }
                },
        ) {
            CoverImage(
                coverUrl = entry.coverUrl,
                contentDescription = null,
                downloaded = entry.downloadStatus == DownloadStatus.DONE,
                format = entry.format,
                seriesIndex = entry.seriesIndex,
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
                    }" + if (entry.downloadStatus == DownloadStatus.DONE) ", downloaded" else ""
                },
        ) {
            CoverImage(
                coverUrl = entry.coverUrl,
                contentDescription = null,
                progress = entry.percent,
                downloaded = entry.downloadStatus == DownloadStatus.DONE,
                format = entry.format,
                seriesIndex = entry.seriesIndex,
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
    seriesIndex: Double?,
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
                    seriesIndex = seriesIndex,
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
        onRemoveFromContinue = actions.onRemoveFromContinue,
        catalogOnly = actions.catalogOnly,
    )
}
