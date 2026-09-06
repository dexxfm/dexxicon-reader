package net.dexxicon.reader.feature.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.feature.catalog.navigation.BookDetailRoute
import javax.inject.Inject

data class BookDetailUiState(
    val loading: Boolean = true,
    val detail: BookDetail? = null,
    val error: String? = null,
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<BookDetailRoute>()

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = catalogRepository.detail(route.serverId, route.bookId)) {
                is Outcome.Success -> _uiState.value =
                    BookDetailUiState(loading = false, detail = result.value)
                is Outcome.Failure -> _uiState.value =
                    BookDetailUiState(loading = false, error = result.error.message ?: "Couldn't load this book")
            }
        }
    }
}
