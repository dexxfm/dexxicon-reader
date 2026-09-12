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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.BookActions
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.fileExtension
import net.dexxicon.reader.feature.catalog.navigation.BookDetailRoute
import javax.inject.Inject

data class BookDetailUiState(
    val loading: Boolean = true,
    val detail: BookDetail? = null,
    val error: String? = null,
    /** Every server carrying this exact book. Size 1 unless opened from merged Browse. */
    val copies: List<BookCopy> = emptyList(),
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val downloadRepository: DownloadRepository,
    private val serverRepository: ServerRepository,
    private val bookActions: BookActions,
    progressRepository: ReadingProgressRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<BookDetailRoute>()

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    val download: StateFlow<Download?> =
        downloadRepository.download(route.serverId, route.bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reading position for the Phase 4 (issue #115) progress row on the detail screen —
     * the same [ReadingProgressRepository] the Home shelves already read from, just scoped
     * to this one book instead of every in-progress book. */
    val progress: StateFlow<ReadingProgress?> =
        progressRepository.observe(route.serverId, route.bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(copies = resolveCopies()) }

            when (val result = catalogRepository.detail(route.serverId, route.bookId)) {
                is Outcome.Success -> _uiState.update {
                    it.copy(loading = false, detail = result.value, error = null)
                }
                is Outcome.Failure -> {
                    // Offline? Fall back to what the downloaded copy remembers.
                    val offline = downloadRepository.get(route.serverId, route.bookId)
                        ?.takeIf { it.status == DownloadStatus.DONE }
                        ?.toBookDetail()
                    _uiState.update {
                        if (offline != null) {
                            it.copy(loading = false, detail = offline, error = null)
                        } else {
                            it.copy(
                                loading = false,
                                error = result.error.message ?: "Couldn't load this book",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The set of servers carrying this book. From the encoded `copies` route arg when
     * opened from merged Browse; otherwise just the one copy in the route.
     */
    private suspend fun resolveCopies(): List<BookCopy> {
        val encoded = route.copies
            .split('|')
            .mapNotNull { part ->
                val (sid, bid) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                sid to bid
            }
            .ifEmpty { listOf(route.serverId to route.bookId) }
        return encoded.map { (sid, bid) ->
            BookCopy(
                serverId = sid,
                serverName = serverRepository.get(sid)?.displayName ?: "Library",
                bookId = bid,
            )
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

    fun setReadingStatus(status: ReadingStatus) {
        val copies = _uiState.value.copies
            .map { it.serverId to it.bookId }
            .ifEmpty { listOf(route.serverId to route.bookId) }
        bookActions.setReadingStatus(copies, status)
        _uiState.update { it.copy(detail = it.detail?.copy(readingStatus = status)) }
    }
}
