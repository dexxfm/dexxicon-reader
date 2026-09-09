package net.dexxicon.reader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.BookActions
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.BookSummary
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

/** A book the user flagged "Want to read" on a server — shown in the On Deck shelf. */
data class OnDeckItem(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val format: ContentFormat,
)

data class LibraryUiState(
    val onDeck: List<OnDeckItem> = emptyList(),
    val continueReading: List<ContinueItem> = emptyList(),
    val continueListening: List<ContinueItem> = emptyList(),
    val downloads: List<Download> = emptyList(),
    /** Reading progress (0–1) for the Downloaded grid, keyed by "serverId::bookId". */
    val downloadProgress: Map<String, Float> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val progressRepository: ReadingProgressRepository,
    private val catalogRepository: CatalogRepository,
    private val bookActions: BookActions,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val onDeck = MutableStateFlow<List<OnDeckItem>>(emptyList())

    val uiState: StateFlow<LibraryUiState> =
        combine(
            downloadRepository.downloads,
            progressRepository.observeAll(),
            onDeck,
            refreshing,
        ) { downloads, progressByKey, onDeckItems, isRefreshing ->
            val inProgress = progressByKey.values
                .filter { it.isInProgress }
                .sortedByDescending { it.updatedAt }
            val items = inProgress.mapNotNull { it.toContinueItem() }
            val inProgressKeys = inProgress.map { "${it.serverId}::${it.bookId}" }.toSet()
            LibraryUiState(
                // A book that's been started isn't "on deck" any more, whatever the server says.
                onDeck = onDeckItems.filter { "${it.serverId}::${it.bookId}" !in inProgressKeys },
                continueReading = items.filter { it.format != ContentFormat.AUDIOBOOK },
                continueListening = items.filter { it.format == ContentFormat.AUDIOBOOK },
                downloads = downloads,
                downloadProgress = progressByKey
                    .mapValues { (_, p) -> (p.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }
                    .filterValues { it > 0f },
                loading = false,
                refreshing = isRefreshing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Reconcile progress with KOReader / other devices (both directions).
        refresh()
    }

    fun refresh() {
        loadOnDeck()
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            runCatching { progressRepository.syncProgress() }
            refreshing.value = false
        }
    }

    private fun loadOnDeck() {
        viewModelScope.launch {
            when (val result = catalogRepository.onDeck()) {
                is Outcome.Success -> onDeck.value = result.value.map { it.toOnDeckItem() }
                is Outcome.Failure -> Unit // keep whatever's already there
            }
        }
    }

    private fun BookSummary.toOnDeckItem() = OnDeckItem(
        serverId = serverId,
        bookId = id,
        title = title,
        author = authorLine.takeIf { it.isNotBlank() },
        coverUrl = coverUrl,
        format = format,
    )

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
