package net.dexxicon.reader.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.DownloadStatus
import javax.inject.Inject

data class BrowseUiState(
    val query: String = "",
    val sort: BookSort = BookSort.RECENT,
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

    init {
        reload()
        viewModelScope.launch {
            _uiState.map { it.query }
                .distinctUntilChanged()
                .drop(1)
                .debounce(350)
                .collect { reload() }
        }
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun onSortSelected(sort: BookSort) {
        if (sort == _uiState.value.sort) return
        _uiState.update { it.copy(sort = sort) }
        reload()
    }

    fun reload() {
        nextPage = 0
        _uiState.update { it.copy(loading = true, error = null, books = emptyList(), endReached = false) }
        fetchPage(replace = true)
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        nextPage = 0
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

    private fun fetchPage(replace: Boolean) = viewModelScope.launch {
        val state = _uiState.value
        when (
            val result = catalogRepository.allBooks(
                query = state.query.takeIf { it.isNotBlank() },
                sort = state.sort,
                page = nextPage,
            )
        ) {
            is Outcome.Success -> {
                nextPage += 1
                _uiState.update {
                    val merged = if (replace) result.value.books else it.books + result.value.books
                    it.copy(
                        books = merged.distinctBy { book -> book.key },
                        loading = false,
                        loadingMore = false,
                        refreshing = false,
                        endReached = !result.value.hasMore,
                        error = null,
                    )
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
