package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.ContentFilterChips
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.component.ViewModeToggle
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.library.BookOverlays

/** What the long-press menu on a Server Browser card needs. */
private data class BooksItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onSetStatus: (ReadingStatus) -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

/**
 * A single server's book catalog (issue #80) — search, sort, content-format filter, grid/list
 * toggle, "Load more" pagination, and the same long-press context menu (mark read/status/
 * download) and progress/download cover overlays [net.dexxicon.reader.shared.library.LibraryContent]
 * has, per the user's "Server Browser should have the same options as Library" request — see
 * [BooksState]'s doc comment for why this is its own implementation rather than literally
 * sharing `LibraryState`/`LibraryContent` (different
 * [net.dexxicon.reader.core.data.CatalogRepository] method, different result type). Covers
 * load through [AppContainer.imageLoader] via [CoverImage] — the same authenticated Ktor
 * client every other call uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    container: AppContainer,
    serverId: String,
    onBack: () -> Unit,
    onOpenBook: (bookId: String) -> Unit,
    onOpenReader: OnOpenReader,
) {
    val scope = rememberCoroutineScope()
    val state = remember(serverId) { BooksState(container, serverId, scope) }
    val serverName by state.serverName.collectAsState()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(onBack)
                Spacer(Modifier.width(12.dp))
                Text(serverName, style = MaterialTheme.typography.headlineSmall)
            }
            BooksBody(state = state, container = container, onOpenBook = onOpenBook, onOpenReader = onOpenReader)
        }
    }
}

@Composable
private fun BooksBody(
    state: BooksState,
    container: AppContainer,
    onOpenBook: (bookId: String) -> Unit,
    onOpenReader: OnOpenReader,
) {
    val overlays by state.overlays.collectAsState()

    fun onTapCover(book: BookSummary) = when (state.coverTapAction) {
        CoverTapAction.OPEN_DETAILS -> onOpenBook(book.id)
        CoverTapAction.OPEN_BOOK -> state.openBook(book, onOpenReader)
    }

    fun actionsFor(book: BookSummary): BooksItemActions {
        val key = "${book.serverId}::${book.id}"
        return BooksItemActions(
            downloadStatus = if (key in overlays.downloaded) DownloadStatus.DONE else null,
            onMarkRead = { state.markRead(book.id) },
            onMarkUnread = { state.markUnread(book.id) },
            onSetStatus = { state.setReadingStatus(book.id, it) },
            onDetails = { onOpenBook(book.id) },
            onDownloadOrRemove = {
                state.downloadOrRemove(book.id, if (key in overlays.downloaded) DownloadStatus.DONE else null)
            },
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        OutlinedTextField(
            value = state.queryDraft,
            onValueChange = { state.queryDraft = it },
            label = { Text("Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { state.search() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        ContentFilterChips(state.filter, state::onFilterSelected)

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val next = BookSort.entries[(state.sort.ordinal + 1) % BookSort.entries.size]
            FilterChip(
                selected = false,
                onClick = { state.onSortChange(next) },
                label = { Text("Sort: ${state.sort.name.lowercase()}") },
            )
            ViewModeToggle(state.viewMode, state::toggleViewMode)
        }
    }

    BooksGrid(
        state = state,
        overlays = overlays,
        onTapCover = ::onTapCover,
        actionsFor = ::actionsFor,
    )
}

private fun AggregatedProgress(book: BookSummary, overlays: BookOverlays): Float? =
    overlays.progress["${book.serverId}::${book.id}"]

@Composable
private fun BooksGrid(
    state: BooksState,
    overlays: BookOverlays,
    onTapCover: (BookSummary) -> Unit,
    actionsFor: (BookSummary) -> BooksItemActions,
) {
    when {
        state.error != null -> CenterMessage {
            Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = state::retry) { Text("Retry") }
        }
        state.loading -> CenterMessage { CircularProgressIndicator() }
        state.books.isEmpty() -> CenterMessage {
            Text("No books match this filter.", style = MaterialTheme.typography.bodyMedium)
        }
        state.viewMode == BookViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItems(state.books, key = { it.id }) { book ->
                BookGridCard(
                    book = book,
                    progress = AggregatedProgress(book, overlays),
                    downloaded = "${book.serverId}::${book.id}" in overlays.downloaded,
                    onClick = { onTapCover(book) },
                    actions = actionsFor(book),
                )
            }
            loadMoreItem(state)
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(state.books, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    progress = AggregatedProgress(book, overlays),
                    downloaded = "${book.serverId}::${book.id}" in overlays.downloaded,
                    onClick = { onTapCover(book) },
                    actions = actionsFor(book),
                )
                HorizontalDivider()
            }
            loadMoreItem(state)
        }
    }
}

private fun LazyGridScope.loadMoreItem(state: BooksState) {
    if (!state.hasMore) return
    item(span = { GridItemSpan(maxLineSpan) }) { LoadMoreRow(state) }
}

private fun LazyListScope.loadMoreItem(state: BooksState) {
    if (!state.hasMore) return
    item { LoadMoreRow(state) }
}

@Composable
private fun LoadMoreRow(state: BooksState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
        if (state.loadingMore) {
            CircularProgressIndicator(Modifier.padding(8.dp))
        } else {
            TextButton(onClick = state::loadMore) { Text("Load more") }
        }
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: BookSummary,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: BooksItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val subtitle = buildString {
        if (book.authorLine.isNotBlank()) append(book.authorLine)
        append(if (isEmpty()) "" else " · ")
        append(book.format.name.lowercase())
        if (pct != null) append(" · $pct% read")
    }
    Box {
        ListItem(
            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Box(Modifier.width(44.dp)) {
                    CoverImage(book.coverUrl, contentDescription = null, progress = progress, downloaded = downloaded)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = "${book.title}, ${book.authorLine}, $subtitle"
                },
        )
        BooksMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    book: BookSummary,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: BooksItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val label = buildString {
        append(book.title)
        if (book.authorLine.isNotBlank()) append(", ${book.authorLine}")
        if (pct != null) append(", $pct% read")
        if (downloaded) append(", downloaded")
    }
    Box {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .semantics(mergeDescendants = true) { contentDescription = label },
        ) {
            CoverImage(
                coverUrl = book.coverUrl,
                contentDescription = null,
                progress = progress,
                downloaded = downloaded,
                format = book.format,
            )
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (book.authorLine.isNotBlank()) {
                Text(
                    book.authorLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BooksMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@Composable
private fun BooksMenu(expanded: Boolean, onDismiss: () -> Unit, actions: BooksItemActions) {
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
