package net.dexxicon.reader.feature.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.feature.catalog.navigation.CatalogRoute
import javax.inject.Inject

data class CatalogUiState(
    val serverName: String = "",
    val shelves: List<CatalogShelf> = emptyList(),
    val selectedShelfId: String? = null,
    val query: String = "",
    val sort: BookSort = BookSort.RECENT,
    val books: List<BookSummary> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CatalogRoute>()
    val serverId: String = route.serverId

    private val _uiState = MutableStateFlow(CatalogUiState(serverName = route.serverName))
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private var nextPage = 0

    init {
        loadShelves()
        reload()
        viewModelScope.launch {
            _uiState.map { it.query }.drop(1).debounce(350).collect { reload() }
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

    fun reload() {
        nextPage = 0
        _uiState.update { it.copy(loading = true, error = null, books = emptyList(), endReached = false) }
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
            val result = catalogRepository.books(
                serverId = serverId,
                shelfId = state.selectedShelfId,
                query = state.query.takeIf { it.isNotBlank() },
                sort = state.sort,
                page = nextPage,
            )
        ) {
            is Outcome.Success -> {
                nextPage += 1
                _uiState.update {
                    it.copy(
                        books = if (replace) result.value.books else it.books + result.value.books,
                        loading = false,
                        loadingMore = false,
                        endReached = !result.value.hasMore,
                        error = null,
                    )
                }
            }
            is Outcome.Failure -> _uiState.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = result.error.message ?: "Couldn't load the catalog",
                )
            }
        }
    }
}
