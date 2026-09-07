package net.dexxicon.reader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.BookActions
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import javax.inject.Inject

data class ContinueItem(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val format: ContentFormat,
    val percent: Float,
)

data class LibraryUiState(
    val continueReading: List<ContinueItem> = emptyList(),
    val continueListening: List<ContinueItem> = emptyList(),
    val downloads: List<Download> = emptyList(),
    /** Reading progress (0–1) for the Downloaded grid, keyed by "serverId::bookId". */
    val downloadProgress: Map<String, Float> = emptyMap(),
    val viewMode: BookViewMode = BookViewMode.GRID,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val progressRepository: ReadingProgressRepository,
    private val bookActions: BookActions,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val viewMode = MutableStateFlow(BookViewMode.GRID)

    val uiState: StateFlow<LibraryUiState> =
        combine(
            downloadRepository.downloads,
            progressRepository.observeAll(),
            refreshing,
            viewMode,
        ) { downloads, progressByKey, isRefreshing, mode ->
            val inProgress = progressByKey.values
                .filter { it.isInProgress }
                .sortedByDescending { it.updatedAt }
            val items = inProgress.mapNotNull { it.toContinueItem() }
            LibraryUiState(
                continueReading = items.filter { it.format != ContentFormat.AUDIOBOOK },
                continueListening = items.filter { it.format == ContentFormat.AUDIOBOOK },
                downloads = downloads,
                downloadProgress = progressByKey
                    .mapValues { (_, p) -> (p.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }
                    .filterValues { it > 0f },
                viewMode = mode,
                loading = false,
                refreshing = isRefreshing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Reconcile progress with KOReader / other devices (both directions).
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            runCatching { progressRepository.syncProgress() }
            refreshing.value = false
        }
    }

    fun toggleViewMode() = viewMode.update {
        if (it == BookViewMode.LIST) BookViewMode.GRID else BookViewMode.LIST
    }

    fun markRead(serverId: String, bookId: String) = bookActions.markFinished(serverId, bookId, true)
    fun markUnread(serverId: String, bookId: String) = bookActions.markFinished(serverId, bookId, false)
    fun setReadingStatus(serverId: String, bookId: String, status: ReadingStatus) =
        bookActions.setReadingStatus(serverId, bookId, status)
    fun downloadOrRemove(serverId: String, bookId: String, status: DownloadStatus?) =
        bookActions.downloadOrRemove(serverId, bookId, status)

    fun remove(download: Download) {
        viewModelScope.launch { downloadRepository.remove(download.serverId, download.bookId) }
    }

    private fun ReadingProgress.toContinueItem(): ContinueItem? {
        val fmt = format ?: return null
        val name = title?.takeIf { it.isNotBlank() } ?: return null
        return ContinueItem(
            serverId = serverId,
            bookId = bookId,
            title = name,
            author = author,
            coverUrl = coverUrl,
            format = fmt,
            percent = (percent ?: 0.0).toFloat().coerceIn(0f, 1f),
        )
    }
}
