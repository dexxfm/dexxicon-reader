package net.dexxicon.reader.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import net.dexxicon.reader.core.data.BookActions
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import javax.inject.Inject

data class BrowseUiState(
    val query: String = "",
    val sort: BookSort = BookSort.RECENT,
    val filter: ContentFilter = ContentFilter.ALL,
    val viewMode: BookViewMode = BookViewMode.LIST,
    val books: List<AggregatedBook> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val bookActions: BookActions,
    progressRepository: ReadingProgressRepository,
    downloadRepository: DownloadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    /** Per-book overlays keyed by "serverId::bookId" — a row matches on any of its copies. */
    val overlays: StateFlow<BookOverlays> =
        combine(
            progressRepository.observeAll(),
            downloadRepository.downloads,
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
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookOverlays())

    private var nextPage = 0

    private companion object {
        const val MAX_AUTO_PAGES = 12
    }

    init {
        reload()
        viewModelScope.launch {
            _uiState.map { it.query }
                .distinctUntilChanged()
                .drop(1)
                .debounce(350)
                .collect { reload() }
        }
        // A server added (or removed) in Settings must show up here without a manual refresh.
        viewModelScope.launch {
            catalogRepository.serverIds
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

    fun toggleViewMode() = _uiState.update {
        it.copy(
            viewMode = if (it.viewMode == BookViewMode.LIST) BookViewMode.GRID else BookViewMode.LIST,
        )
    }

    fun markRead(copies: List<Pair<String, String>>) = bookActions.markFinished(copies, true)
    fun markUnread(copies: List<Pair<String, String>>) = bookActions.markFinished(copies, false)
    fun setReadingStatus(copies: List<Pair<String, String>>, status: ReadingStatus) =
        bookActions.setReadingStatus(copies, status)
    fun downloadOrRemove(serverId: String, bookId: String, status: DownloadStatus?) =
        bookActions.downloadOrRemove(serverId, bookId, status)

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

    private fun fetchPage(replace: Boolean): Job = viewModelScope.launch {
        val state = _uiState.value
        when (
            val result = catalogRepository.allBooks(
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
