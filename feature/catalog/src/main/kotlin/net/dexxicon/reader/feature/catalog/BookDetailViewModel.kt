package net.dexxicon.reader.feature.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.fileExtension
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
    private val downloadRepository: DownloadRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<BookDetailRoute>()

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    val download: StateFlow<Download?> =
        downloadRepository.download(route.serverId, route.bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            when (val result = catalogRepository.detail(route.serverId, route.bookId)) {
                is Outcome.Success -> _uiState.value =
                    BookDetailUiState(loading = false, detail = result.value)
                is Outcome.Failure -> {
                    // Offline? Fall back to what the downloaded copy remembers.
                    val offline = downloadRepository.get(route.serverId, route.bookId)
                        ?.takeIf { it.status == DownloadStatus.DONE }
                        ?.toBookDetail()
                    _uiState.value = if (offline != null) {
                        BookDetailUiState(loading = false, detail = offline)
                    } else {
                        BookDetailUiState(
                            loading = false,
                            error = result.error.message ?: "Couldn't load this book",
                        )
                    }
                }
            }
        }
    }

    private fun Download.toBookDetail(): BookDetail = BookDetail(
        summary = BookSummary(
            id = bookId,
            serverId = serverId,
            title = title,
            authors = authors,
            series = series,
            coverUrl = coverUrl,
            format = format,
        ),
        fileExtension = localPath?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
            ?: format.fileExtension,
        fileSizeBytes = totalBytes,
        acquisitions = localPath?.let {
            listOf(
                Acquisition(
                    href = it,
                    mediaType = format.name,
                    format = format,
                    relation = AcquisitionRelation.OPEN_ACCESS,
                ),
            )
        } ?: emptyList(),
    )

    fun onDownload() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch { downloadRepository.enqueue(detail) }
    }

    fun onRemoveDownload() {
        viewModelScope.launch { downloadRepository.remove(route.serverId, route.bookId) }
    }
}
