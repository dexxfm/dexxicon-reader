package net.dexxicon.reader.shared.library

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import net.dexxicon.reader.core.model.Facet
import net.dexxicon.reader.core.model.FacetGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.designsystem.component.BookContextMenu
import net.dexxicon.reader.core.designsystem.component.ContentFilterChips
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.component.LocalCoverBadges
import net.dexxicon.reader.core.model.seriesPositionText
import net.dexxicon.reader.core.designsystem.component.ViewModeToggle
import net.dexxicon.reader.core.designsystem.nav.FloatingNavClearance
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.SeriesEntry
import net.dexxicon.reader.shared.OnOpenReader

/** What the long-press menu on a Library card needs to act on and render. */
private data class LibraryItemActions(
    val downloadStatus: DownloadStatus?,
    val onMarkRead: () -> Unit,
    val onMarkUnread: () -> Unit,
    val onSetStatus: (ReadingStatus) -> Unit,
    val onDetails: () -> Unit,
    val onDownloadOrRemove: () -> Unit,
)

/**
 * Phase 4 Stage D (issue #133) — the one Library composable, ported as-is from native's
 * `feature/catalog/BrowseScreen.kt` (same search/sort/filter/grid-list toggle/infinite
 * scroll/context menu), reading from [LibraryState] instead of a Hilt `BrowseViewModel`. A
 * cover tap resolves to either [onOpenBook] or [LibraryState.openBook] depending on
 * [LibraryUiState.coverTapAction] — see [net.dexxicon.reader.core.designsystem.component]
 * for [CoverTapAction]'s own doc comment on why Home's shelves never consult this setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryState,
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: OnOpenReader,
    onOpenSeries: (SeriesEntry) -> Unit,
    onOpenGroup: (BookGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Library") }) },
        // See HomeContent.kt's matching Scaffold for why — the app shell already accounts
        // for the bottom nav / system inset; without this the Scaffold reserves it again.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Batch B (issues #253, #254, #256) — Books keeps the merged grid (now with a
            // library picker); Series and Collections browse the servers' own groupings.
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { state.tab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }
            when (state.tab) {
                LibraryTab.BOOKS -> LibraryBooksPane(
                    state = state,
                    onOpenBook = onOpenBook,
                    onOpenReader = onOpenReader,
                    header = { LibraryPicker(state) },
                )
                LibraryTab.SERIES -> SeriesPane(state.series, onOpenSeries)
                LibraryTab.COLLECTIONS -> CollectionsPane(state.collections, onOpenGroup)
            }
        }
    }
}

/**
 * The searchable, filterable, sortable book grid/list — the main Library's Books tab, and the
 * whole body of a library/collection/series screen ([BookGroupScreen]). [header] sits between
 * the search field and the format chips (the Books tab's library picker).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryBooksPane(
    state: LibraryState,
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: OnOpenReader,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val uiState by state.uiState.collectAsState()
    val overlays by state.overlays.collectAsState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    fun onTapCover(book: AggregatedBook) = when (uiState.coverTapAction) {
        CoverTapAction.OPEN_DETAILS -> onOpenBook(book)
        CoverTapAction.OPEN_BOOK -> state.openBook(book, onOpenReader)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = if (uiState.viewMode == BookViewMode.GRID) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } ?: return@derivedStateOf false
            uiState.books.isNotEmpty() && last >= uiState.books.size - 6
        }
    }
    // issue #195 — keyed on `uiState.loadingMore` too, not just `shouldLoadMore` alone: a
    // content-format filter that matches only a handful of a large catalogue (Audiobooks is
    // the common case) can satisfy "near the end of the list" on page 1 and then *stay*
    // satisfied through every later page, since the filtered list never grows past the
    // viewport. `LaunchedEffect` only restarts its block on a key *change*, so keying on
    // `shouldLoadMore` alone fired loadMore() exactly once (on the false->true edge) and then
    // never again — silently stranding the rest of that format's books unfetched, since
    // nothing else was ever going to call loadMore() again. `loadingMore` flips true->false
    // once per fetch regardless of whether that page's filtered contribution was empty, so
    // including it re-evaluates `shouldLoadMore` after every single page — continuing to
    // page through until either enough results are found, or `loadMore()`'s own guards
    // (`endReached`/`error`/empty book list) stop it.
    LaunchedEffect(shouldLoadMore, uiState.loadingMore) {
        if (shouldLoadMore) state.loadMore()
    }

    fun actionsFor(book: AggregatedBook): LibraryItemActions {
        val c = book.primary
        // Status/mark-read applies to every copy so the servers don't disagree.
        val targets = book.copies.map { it.serverId to it.bookId }
        return LibraryItemActions(
            downloadStatus = if (book.downloadedIn(overlays)) DownloadStatus.DONE else null,
            onMarkRead = { state.markRead(targets) },
            onMarkUnread = { state.markUnread(targets) },
            onSetStatus = { state.setReadingStatus(targets, it) },
            onDetails = { onOpenBook(book) },
            onDownloadOrRemove = {
                state.downloadOrRemove(
                    c.serverId,
                    c.bookId,
                    if (book.downloadedIn(overlays)) DownloadStatus.DONE else null,
                )
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (!uiState.scope.isSeries) TextField(
            value = uiState.query,
            onValueChange = state::onQueryChange,
            placeholder = { Text("Search titles, authors…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        header()

        ContentFilterChips(uiState.filter, state::onFilterSelected)

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val catalogSort = uiState.facets.firstOrNull { it.isSort }
            if (uiState.scope.isSeries) {
                // A series is always shown in series order — there's nothing to sort.
                Text(
                    "In series order",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!uiState.appSortApplies) {
                // issue #293 — an OPDS catalog sorts only its own way, if at all.
                if (catalogSort != null) {
                    FacetSortChip(catalogSort, state::onFacetSelected)
                } else {
                    Text(
                        "Catalog order",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val next = BookSort.entries[(uiState.sort.ordinal + 1) % BookSort.entries.size]
                FilterChip(
                    selected = false,
                    onClick = { state.onSortSelected(next) },
                    label = { Text("Sort: ${uiState.sort.name.lowercase()}") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val filters = uiState.facets.filterNot { it.isSort }
                if (filters.isNotEmpty()) FacetFiltersChip(filters, state::onFacetSelected)
                ViewModeToggle(uiState.viewMode, state::toggleViewMode)
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = state::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                uiState.loading -> CenterBox { CircularProgressIndicator() }
                uiState.error != null && uiState.books.isEmpty() -> CenterBox {
                    Text(
                        uiState.error ?: "",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                uiState.books.isEmpty() -> CenterBox {
                    Text(
                        when {
                            uiState.query.isNotBlank() -> "No books match “${uiState.query}”."
                            uiState.filter != ContentFilter.ALL -> "No ${uiState.filter.label.lowercase()} here."
                            uiState.scope != LibraryScope.All -> "Nothing here yet."
                            else -> "Add a server in Settings to start browsing."
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                uiState.viewMode == BookViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    // issue #132 — clears the floating nav (and mini-player, when
                    // showing), which now overlays content instead of reserving space.
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        bottom = FloatingNavClearance,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    gridItems(uiState.books, key = { it.key }) { book ->
                        LibraryGridCard(
                            book = book,
                            progress = book.progressFrom(overlays),
                            downloaded = book.downloadedIn(overlays),
                            onClick = { onTapCover(book) },
                            actions = actionsFor(book),
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = FloatingNavClearance),
                ) {
                    items(uiState.books, key = { it.key }) { book ->
                        LibraryRow(
                            book = book,
                            progress = book.progressFrom(overlays),
                            downloaded = book.downloadedIn(overlays),
                            onClick = { onTapCover(book) },
                            actions = actionsFor(book),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun AggregatedBook.progressFrom(overlays: BookOverlays): Float? =
    copies.mapNotNull { overlays.progress["${it.serverId}::${it.bookId}"] }.maxOrNull()

private fun AggregatedBook.downloadedIn(overlays: BookOverlays): Boolean =
    copies.any { "${it.serverId}::${it.bookId}" in overlays.downloaded }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: LibraryItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val libraries = book.copies.size
    val series = seriesPositionText(book.series, book.seriesIndex)
        ?.takeIf { LocalCoverBadges.current.showSeriesNumber }
    val subtitle = buildString {
        if (book.authorLine.isNotBlank()) append(book.authorLine)
        // issue #257 — list rows' covers are too small for the "#3" badge; say it here instead.
        if (series != null) append(if (isEmpty()) "" else " · ").append(series)
        append(if (isEmpty()) "" else " · ")
        append(book.format.name.lowercase())
        if (libraries > 1) append(" · On $libraries libraries")
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
        LibraryMenu(menuOpen, { menuOpen = false }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridCard(
    book: AggregatedBook,
    progress: Float?,
    downloaded: Boolean,
    onClick: () -> Unit,
    actions: LibraryItemActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pct = progress?.let { (it * 100).toInt() }
    val secondary = if (book.copies.size > 1) "On ${book.copies.size} libraries" else book.authorLine
    val series = seriesPositionText(book.series, book.seriesIndex)
        ?.takeIf { LocalCoverBadges.current.showSeriesNumber }
    val label = buildString {
        append(book.title)
        if (book.authorLine.isNotBlank()) append(", ${book.authorLine}")
        if (series != null) append(", $series")
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
                seriesIndex = book.seriesIndex,
            )
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (secondary.isNotBlank()) {
                Text(
                    secondary,
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

@Composable
private fun LibraryMenu(expanded: Boolean, onDismiss: () -> Unit, actions: LibraryItemActions) {
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

/** issue #293 — a catalog's own sort order (an OPDS sort facet group), as a menu. */
@Composable
private fun FacetSortChip(group: FacetGroup, onSelect: (Facet) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text("Sort: ${group.active?.title?.lowercase() ?: "catalog order"}") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            group.facets.forEach { facet ->
                DropdownMenuItem(
                    text = { Text(facet.title) },
                    leadingIcon = if (facet.active) {
                        { Icon(Icons.Filled.Check, contentDescription = "Current") }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(facet)
                    },
                )
            }
        }
    }
}

/**
 * issue #293 — a catalog's own filters (its non-sort OPDS facet groups, e.g. Open Library's
 * Availability and Language), each a full-width dropdown in a sheet. The chip counts the groups
 * narrowed from their default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FacetFiltersChip(groups: List<FacetGroup>, onSelect: (Facet) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val narrowed = groups.count { it.isNarrowed }
    FilterChip(
        selected = narrowed > 0,
        onClick = { open = true },
        label = { Text(if (narrowed > 0) "Filters · $narrowed" else "Filters") },
        leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null) },
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Filters", style = MaterialTheme.typography.titleMedium)
                groups.forEach { group ->
                    FacetDropdown(group) { facet ->
                        open = false
                        onSelect(facet)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FacetDropdown(group: FacetGroup, onSelect: (Facet) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = group.active?.title ?: "Any",
            onValueChange = {},
            readOnly = true,
            label = { Text(group.title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            group.facets.forEach { facet ->
                DropdownMenuItem(
                    text = { Text(facet.count?.let { "${facet.title}  ($it)" } ?: facet.title) },
                    onClick = {
                        expanded = false
                        onSelect(facet)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    // Scrollable even when it fits, so pull-to-refresh still works on the empty/error states.
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) { content() }
}
