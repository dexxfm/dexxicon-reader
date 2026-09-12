package net.dexxicon.reader.shared.library

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
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader

data class LibraryUiState(
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
) {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

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
        scope.launch {
            container.appPreferences.preferences.map { it.browseView }.distinctUntilChanged().collect { mode ->
                _uiState.update { it.copy(viewMode = mode) }
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
                .collect { reload() }
        }
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun onSortSelected(sort: BookSort) {
        if (sort == _uiState.value.sort) return
        _uiState.update { it.copy(sort = sort) }
        reload()
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
     * [net.dexxicon.reader.shared.openReader] helper every other tap-to-read site uses. */
    fun openBook(book: AggregatedBook, onOpenReader: OnOpenReader) {
        val serverId = book.primary.serverId
        val bookId = book.primary.bookId
        scope.launch {
            val detail = (container.catalogRepository.detail(serverId, bookId) as? Outcome.Success)
                ?.value ?: return@launch
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

    private fun fetchPage(replace: Boolean): Job = scope.launch {
        val state = _uiState.value
        when (
            val result = container.catalogRepository.allBooks(
                query = state.query.takeIf { it.isNotBlank() },
                sort = state.sort,
                page = nextPage,
                formats = state.filter.formats,
            )
        ) {
            is Outcome.Success -> {
                nextPage += 1
                val endReached = !result.value.hasMore
                val books = _uiState.updateAndGet {
                    val prev: List<AggregatedBook> = if (replace) emptyList() else it.books
                    val merged = prev + result.value.books
                    it.copy(
                        books = merged.distinctBy { book -> book.key },
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
