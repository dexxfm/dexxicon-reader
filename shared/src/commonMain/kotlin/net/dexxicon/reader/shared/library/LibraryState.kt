package net.dexxicon.reader.shared.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.AggregatedBookPage
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.FacetGroup
import net.dexxicon.reader.core.model.Facet
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader
import net.dexxicon.reader.shared.toBookDetail

/**
 * What a [LibraryState] lists (issues #253, #254, #256). The main Library tab starts at [All]
 * and can narrow to one library; the collection/series screens are each a fixed scope.
 */
sealed interface LibraryScope {
    /** Every book on every server, merged. */
    data object All : LibraryScope

    /** The books in [groups] — one library or collection, or one series across servers. */
    data class Groups(val title: String, val groups: List<BookGroup>) : LibraryScope

    /** A series known only by name (opened from a book's series line), looked up on every
     *  server the first time it loads — see [net.dexxicon.reader.core.data.CatalogRepository.seriesNamed]. */
    data class SeriesNamed(val name: String) : LibraryScope

    /** Series come back in series order and can't be searched on every server, so screens
     *  hide their sort chip and search field. */
    val isSeries: Boolean
        get() = this is SeriesNamed || (this is Groups && groups.isNotEmpty() && groups.all { it.kind == BookGroupKind.SERIES })
}

/** The main Library's tabs (Batch B — issues #253, #254, #256). */
enum class LibraryTab(val label: String) { BOOKS("Books"), SERIES("Series"), COLLECTIONS("Collections") }

data class LibraryUiState(
    val scope: LibraryScope = LibraryScope.All,
    val query: String = "",
    val sort: BookSort = BookSort.RECENT,
    val filter: ContentFilter = ContentFilter.ALL,
    val viewMode: BookViewMode = BookViewMode.GRID,
    val coverTapAction: CoverTapAction = CoverTapAction.OPEN_DETAILS,
    val books: List<AggregatedBook> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    /** issue #293 — the current list's own sort/filter choices (one OPDS catalog's facets). */
    val facets: List<FacetGroup> = emptyList(),
    /** The facet chosen from [facets], listed instead of the scope's usual books. */
    val facetHref: String? = null,
    /** False for a catalog that only sorts through its own facets — see [BookPage.appSortApplies]. */
    val appSortApplies: Boolean = true,
)

/** Per-book overlays keyed by "serverId::bookId" — a row matches on any of its copies. */
data class BookOverlays(
    val progress: Map<String, Float> = emptyMap(),
    val downloaded: Set<String> = emptySet(),
)

/**
 * Phase 4 Stage D (issue #133) — the one Library state class, replacing native's Hilt
 * `BrowseViewModel` (`feature/catalog/BrowseViewModel.kt`). Ports its logic verbatim: the
 * merged, de-duplicated grid across every configured server — debounced search, sort,
 * content-format filter, a persisted grid/list toggle, capped auto-paging past
 * filter-empty pages, and the same per-book progress/download overlays. Built from
 * [AppContainer] + a [CoroutineScope], constructed once (same shape as
 * [net.dexxicon.reader.shared.home.HomeState] — no per-item key).
 *
 * Replaces `:shared`'s previous thin `catalog/BrowseState.kt` (issue #82 MVP — no filter
 * chips, no infinite scroll, no context menu, and a since-superseded "Continue reading"
 * on-deck shelf duplicating what Stage C's Home shelves already do properly).
 */
@OptIn(FlowPreview::class)
class LibraryState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    initialScope: LibraryScope = LibraryScope.All,
) {
    private val _uiState = MutableStateFlow(LibraryUiState(scope = initialScope))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** issue #253 — every server's libraries, for the Books tab's library picker. Only the
     *  main Library (an [LibraryScope.All]-rooted state) ever loads these. */
    private val _libraries = MutableStateFlow<List<BookGroup>>(emptyList())
    val libraries: StateFlow<List<BookGroup>> = _libraries.asStateFlow()
    private val browsesAll = initialScope == LibraryScope.All

    /** [LibraryScope.SeriesNamed] resolves to real groups once, then reuses them. */
    private var resolvedSeries: List<BookGroup>? = null

    /** Which tab the main Library shows. Held here rather than in the composable so it
     *  survives the two-pane layout's Library pane coming and going (see `App.kt`). */
    var tab by mutableStateOf(LibraryTab.BOOKS)

    /** Created on first use, i.e. the first time each tab is opened. */
    val series: SeriesListState by lazy { SeriesListState(container, scope) }
    val collections: CollectionsState by lazy { CollectionsState(container, scope) }

    val overlays: StateFlow<BookOverlays> =
        combine(
            container.progressRepository.observeAll(),
            container.downloadRepository.downloads,
        ) { progress, downloads ->
            BookOverlays(
                progress = progress
                    .mapValues { (_, p) -> (p.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }
                    .filterValues { it > 0f },
                downloaded = downloads
                    .filter { it.status == DownloadStatus.DONE }
                    .map { "${it.serverId}::${it.bookId}" }
                    .toSet(),
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), BookOverlays())

    private var nextPage = 0

    private companion object {
        const val MAX_AUTO_PAGES = 12
    }

    init {
        reload()
        if (browsesAll) loadLibraries()
        scope.launch {
            container.appPreferences.preferences.map { it.browseView }.distinctUntilChanged().collect { mode ->
                _uiState.update { it.copy(viewMode = mode) }
            }
        }
        scope.launch {
            container.appPreferences.preferences.map { it.browseSort }.distinctUntilChanged().collect { sort ->
                if (sort == _uiState.value.sort) return@collect
                _uiState.update { it.copy(sort = sort) }
                reload()
            }
        }
        scope.launch {
            container.appPreferences.preferences.map { it.coverTapAction }.distinctUntilChanged().collect { action ->
                _uiState.update { it.copy(coverTapAction = action) }
            }
        }
        scope.launch {
            _uiState.map { it.query }
                .distinctUntilChanged()
                .drop(1)
                .debounce(350)
                .collect { reload() }
        }
        // A server added (or removed) in Settings must show up here without a manual refresh.
        scope.launch {
            container.catalogRepository.serverIds
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    // A removed server's library can't stay selected.
                    val current = _uiState.value.scope
                    if (browsesAll && current is LibraryScope.Groups &&
                        current.groups.any { g -> g.serverId !in it }
                    ) {
                        _uiState.update { s -> s.copy(scope = LibraryScope.All) }
                    }
                    resolvedSeries = null
                    reload()
                    if (browsesAll) loadLibraries()
                }
        }
    }

    private fun loadLibraries() {
        scope.launch {
            (container.catalogRepository.groups(BookGroupKind.LIBRARY) as? Outcome.Success)
                ?.let { _libraries.value = it.value }
        }
    }

    /** issue #253 — narrow the Books tab to one library, or back to everything (null). */
    fun selectLibrary(library: BookGroup?) {
        val next = library?.let { LibraryScope.Groups(it.name, listOf(it)) } ?: LibraryScope.All
        if (next == _uiState.value.scope) return
        _uiState.update { it.copy(scope = next, facetHref = null, facets = emptyList(), appSortApplies = true) }
        reload()
    }

    // issue #293 — a chosen facet belongs to the old search's results, so a new search drops it.
    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value, facetHref = null) }

    /** issue #293 — list one of the catalog's own sort/filter choices instead. */
    fun onFacetSelected(facet: Facet) {
        if (facet.href == _uiState.value.facetHref) return
        _uiState.update { it.copy(facetHref = facet.href) }
        reload()
    }

    /** Persists Library's own sort choice — the Settings default is untouched. The actual
     *  [LibraryUiState.sort] update and [reload] happen when that write is reflected back
     *  through the preference collector above, same as [toggleViewMode]. */
    fun onSortSelected(sort: BookSort) {
        if (sort == _uiState.value.sort) return
        scope.launch { container.appPreferences.setBrowseSort(sort) }
    }

    fun onFilterSelected(filter: ContentFilter) {
        if (filter == _uiState.value.filter) return
        _uiState.update { it.copy(filter = filter) }
        reload()
    }

    /** Flips Library's own layout — the Settings default is untouched. */
    fun toggleViewMode() {
        val next = if (_uiState.value.viewMode == BookViewMode.LIST) BookViewMode.GRID else BookViewMode.LIST
        scope.launch { container.appPreferences.setBrowseView(next) }
    }

    fun markRead(copies: List<Pair<String, String>>) = container.bookActions.markFinished(copies, true)
    fun markUnread(copies: List<Pair<String, String>>) = container.bookActions.markFinished(copies, false)
    fun setReadingStatus(copies: List<Pair<String, String>>, status: ReadingStatus) =
        container.bookActions.setReadingStatus(copies, status)
    fun downloadOrRemove(serverId: String, bookId: String, status: DownloadStatus?) =
        container.bookActions.downloadOrRemove(serverId, bookId, status)

    /** A cover tap when [LibraryUiState.coverTapAction] is `OPEN_BOOK` — unlike Book
     * Detail's "Read" button, a grid card only has [AggregatedBook]'s lightweight metadata,
     * so this fetches the full detail first, then hands off through the same
     * [net.dexxicon.reader.shared.openReader] helper every other tap-to-read site uses.
     *
     * issue #248: don't gate on that (network) fetch succeeding — offline, it used to fail
     * and this silently returned before [onOpenReader] ever fired, same bug as Home's
     * Continue reading/listening cards had. issue #250 follow-up: the offline branch
     * reconstructs a real `BookDetail` via [net.dexxicon.reader.shared.toBookDetail] and
     * routes it through [container.openReader] like every other call site, rather than
     * hand-building a partial [OnOpenReader] call with a hardcoded null `audiobook` — see
     * [net.dexxicon.reader.shared.home.HomeState.continueReading]'s doc comment for why that
     * silently broke a downloaded audiobook's duration/position display. */
    fun openBook(book: AggregatedBook, onOpenReader: OnOpenReader) {
        val serverId = book.primary.serverId
        val bookId = book.primary.bookId
        scope.launch {
            val detail = (container.catalogRepository.detail(serverId, bookId) as? Outcome.Success)
                ?.value
                ?: container.downloadRepository.get(serverId, bookId)
                    ?.takeIf { it.status == DownloadStatus.DONE }
                    ?.toBookDetail()
                ?: return@launch
            container.openReader(detail, serverId, bookId, onOpenReader)
        }
    }

    /** Auto-paging past filter-empty pages is capped so a filter with no matches can't
     *  crawl an entire catalogue. */
    private var autoPagesLeft = MAX_AUTO_PAGES

    fun reload() {
        nextPage = 0
        autoPagesLeft = MAX_AUTO_PAGES
        _uiState.update { it.copy(loading = true, error = null, books = emptyList(), endReached = false) }
        fetchPage(replace = true)
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        nextPage = 0
        autoPagesLeft = MAX_AUTO_PAGES
        _uiState.update { it.copy(refreshing = true, error = null, endReached = false) }
        fetchPage(replace = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || state.endReached ||
            state.error != null || state.books.isEmpty()
        ) {
            return
        }
        _uiState.update { it.copy(loadingMore = true) }
        fetchPage(replace = false)
    }

    private suspend fun fetch(state: LibraryUiState): Outcome<AggregatedBookPage> {
        val query = state.query.takeIf { it.isNotBlank() }
        val groups = when (val s = state.scope) {
            LibraryScope.All -> return container.catalogRepository.allBooks(
                query = query,
                sort = state.sort,
                page = nextPage,
                formats = state.filter.formats,
            )
            is LibraryScope.Groups -> s.groups
            is LibraryScope.SeriesNamed -> resolvedSeries
                ?: container.catalogRepository.seriesNamed(s.name).also { resolvedSeries = it }
        }
        return container.catalogRepository.groupBooks(
            groups = groups,
            query = query.takeUnless { state.scope.isSeries },
            sort = state.sort,
            page = nextPage,
            formats = state.filter.formats,
            facetHref = state.facetHref,
        )
    }

    private fun fetchPage(replace: Boolean): Job = scope.launch {
        val state = _uiState.value
        when (val result = fetch(state)) {
            is Outcome.Success -> {
                nextPage += 1
                val endReached = !result.value.hasMore
                val books = _uiState.updateAndGet {
                    val prev: List<AggregatedBook> = if (replace) emptyList() else it.books
                    val merged = prev + result.value.books
                    it.copy(
                        books = merged.distinctBy { book -> book.key },
                        // issue #293 — a list's facets come with its first page.
                        facets = if (replace) result.value.facets else it.facets,
                        appSortApplies = if (replace) result.value.appSortApplies else it.appSortApplies,
                        endReached = endReached,
                        refreshing = false,
                        error = null,
                        // Keep showing the spinner while we auto-page past empty filtered pages.
                        loading = merged.isEmpty() && !endReached,
                        loadingMore = false,
                    )
                }.books
                // A format filter can leave a fetched page with nothing to show — pull the
                // next one until we have results, run out, or hit the cap.
                if (books.isEmpty() && !endReached && autoPagesLeft-- > 0) {
                    fetchPage(replace = false)
                } else if (books.isEmpty()) {
                    _uiState.update { it.copy(loading = false) }
                }
            }
            is Outcome.Failure -> _uiState.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    refreshing = false,
                    error = result.error.message ?: "Couldn't load books",
                )
            }
        }
    }
}
