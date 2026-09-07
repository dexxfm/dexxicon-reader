package net.dexxicon.reader.feature.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
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
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.feature.catalog.navigation.CatalogRoute
import javax.inject.Inject

/** Per-book overlays for the grid: reading progress (0–1) and offline availability. */
data class BookOverlays(
    val progress: Map<String, Float> = emptyMap(),
    val downloaded: Set<String> = emptySet(),
)

data class CatalogUiState(
    val serverName: String = "",
    val shelves: List<CatalogShelf> = emptyList(),
    val selectedShelfId: String? = null,
    val query: String = "",
    val sort: BookSort = BookSort.RECENT,
    val filter: ContentFilter = ContentFilter.ALL,
    val viewMode: BookViewMode = BookViewMode.GRID,
    val books: List<BookSummary> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val bookActions: BookActions,
    progressRepository: ReadingProgressRepository,
    downloadRepository: DownloadRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CatalogRoute>()
    val serverId: String = route.serverId

    private companion object {
        const val MAX_AUTO_PAGES = 12
    }
    private var autoPagesLeft = MAX_AUTO_PAGES

    private val _uiState = MutableStateFlow(CatalogUiState(serverName = route.serverName))
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    val overlays: StateFlow<BookOverlays> =
        combine(
            progressRepository.observeForServer(serverId),
            downloadRepository.downloads,
        ) { progress, downloads ->
            BookOverlays(
                progress = progress
                    .mapValues { (_, p) -> (p.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }
                    .filterValues { it > 0f },
                downloaded = downloads
                    .filter { it.serverId == serverId && it.status == DownloadStatus.DONE }
                    .map { it.bookId }
                    .toSet(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookOverlays())

    private var nextPage = 0

    init {
        loadShelves()
        reload()
        viewModelScope.launch {
            _uiState.map { it.query }
                .distinctUntilChanged()
                .drop(1)
                .debounce(350)
                .collect { reload() }
        }
    }

    private fun loadShelves() = viewModelScope.launch {
        (catalogRepository.shelves(serverId) as? Outcome.Success)?.let { result ->
            _uiState.update { it.copy(shelves = result.value) }
        }
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun onShelfSelected(shelfId: String?) {
        if (shelfId == _uiState.value.selectedShelfId) return
        _uiState.update { it.copy(selectedShelfId = shelfId) }
        reload()
    }

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
        it.copy(viewMode = if (it.viewMode == BookViewMode.LIST) BookViewMode.GRID else BookViewMode.LIST)
    }

    fun markRead(serverId: String, bookId: String) = bookActions.markFinished(serverId, bookId, true)
    fun markUnread(serverId: String, bookId: String) = bookActions.markFinished(serverId, bookId, false)
    fun downloadOrRemove(serverId: String, bookId: String, status: DownloadStatus?) =
        bookActions.downloadOrRemove(serverId, bookId, status)

    fun reload() {
        nextPage = 0
        autoPagesLeft = MAX_AUTO_PAGES
        _uiState.update { it.copy(loading = true, error = null, books = emptyList(), endReached = false) }
        fetchPage(replace = true)
    }

    /** Pull-to-refresh: re-fetch shelves and the first page without blanking the grid. */
    fun refresh() {
        if (_uiState.value.refreshing) return
        nextPage = 0
        autoPagesLeft = MAX_AUTO_PAGES
        _uiState.update { it.copy(refreshing = true, error = null, endReached = false) }
        loadShelves()
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
            val result = catalogRepository.books(
                serverId = serverId,
                shelfId = state.selectedShelfId,
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
                    val prev = if (replace) emptyList() else it.books
                    val merged = (prev + result.value.books).distinctBy { b -> b.id }
                    it.copy(
                        books = merged,
                        endReached = endReached,
                        refreshing = false,
                        error = null,
                        loading = merged.isEmpty() && !endReached,
                        loadingMore = false,
                    )
                }.books
                // A format filter can leave a fetched page with nothing to show — keep
                // pulling pages until we have results, run out, or hit the cap.
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
                    error = result.error.message ?: "Couldn't load the catalog",
                )
            }
        }
    }
}
