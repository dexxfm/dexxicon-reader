package net.dexxicon.reader.feature.reader.pdf

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.BookmarkRepository
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.reader.PublicationStreamer
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import java.io.File
import net.dexxicon.reader.core.reader.ReaderLocatorStore
import net.dexxicon.reader.core.reader.ReaderPreferencesStore
import net.dexxicon.reader.feature.reader.pdf.navigation.PdfReaderRoute
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType
import javax.inject.Inject

sealed interface PdfReaderState {
    data object Loading : PdfReaderState
    data class Error(val message: String) : PdfReaderState
    data class Ready(
        val publication: Publication,
        val initialLocator: Locator?,
        val title: String,
        val pageCount: Int,
    ) : PdfReaderState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val locatorStore: ReaderLocatorStore,
    private val downloadRepository: DownloadRepository,
    private val streamer: PublicationStreamer,
    private val progressRepository: ReadingProgressRepository,
    private val bookmarkRepository: BookmarkRepository,
    preferencesStore: ReaderPreferencesStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PdfReaderRoute>()

    private val _state = MutableStateFlow<PdfReaderState>(PdfReaderState.Loading)
    val state: StateFlow<PdfReaderState> = _state.asStateFlow()

    val preferences: StateFlow<ReaderDisplayPreferences> = preferencesStore.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderDisplayPreferences())

    val updatePreferences: (suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit) =
        preferencesStore::update

    val bookmarks: StateFlow<List<Bookmark>> =
        bookmarkRepository.observe(route.serverId, route.bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val locatorUpdates = MutableSharedFlow<Locator>(extraBufferCapacity = 1)
    private var publication: Publication? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            locatorUpdates.debounce(1_000).collect {
                locatorStore.save(route.serverId, route.bookId, it)
            }
        }
        viewModelScope.launch {
            runCatching { bookmarkRepository.syncFromServer(route.serverId, route.bookId) }
        }
    }

    fun addBookmark(locator: Locator) {
        val page = locator.locations.position
        viewModelScope.launch {
            bookmarkRepository.add(
                serverId = route.serverId,
                bookId = route.bookId,
                locatorJson = locator.toJSON().toString(),
                progression = locator.locations.totalProgression
                    ?: locator.locations.progression ?: 0.0,
                title = page?.let { "Page $it" } ?: "Bookmark",
            )
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { bookmarkRepository.delete(id) }
    }

    private suspend fun load() {
        // DownloadRepository.localFile returns a plain path (issue #126 — java.io.File isn't
        // portable), so this Android-only caller wraps it back into a File itself.
        val localFile = downloadRepository.localFile(route.serverId, route.bookId)?.let(::File)
        val downloadTitle = downloadRepository.get(route.serverId, route.bookId)?.title
        val detail = (catalogRepository.detail(route.serverId, route.bookId) as? Outcome.Success)?.value

        if (localFile == null && detail == null) {
            _state.value = PdfReaderState.Error("Couldn't reach this PDF — download it for offline reading")
            return
        }

        var remoteHref: String? = detail?.acquisitions?.firstOrNull { it.format == ContentFormat.PDF }?.href
            ?: detail?.primaryAcquisition?.href
        val opened = if (localFile != null) {
            streamer.open(localFile, MediaType.PDF)
        } else {
            val acquisition = detail!!.acquisitions.firstOrNull { it.format == ContentFormat.PDF }
                ?: detail.primaryAcquisition
            if (acquisition == null || acquisition.format != ContentFormat.PDF) {
                _state.value = PdfReaderState.Error("This book isn't a PDF")
                return
            }
            remoteHref = acquisition.href
            streamer.open(acquisition.href, MediaType.PDF)
        }

        when (opened) {
            is Outcome.Success -> {
                publication = opened.value
                val local = locatorStore.initialLocator(route.serverId, route.bookId)
                // If the server (web reader / another device) is further along, open there.
                val remote = runCatching {
                    progressRepository.nativeRemoteAhead(
                        route.serverId, route.bookId, ContentFormat.PDF, remoteHref,
                        local?.locations?.totalProgression,
                    )
                }.getOrNull()
                val targetPage = remote?.page ?: local?.locations?.position
                val resumeLocator = targetPage?.let { pg ->
                    opened.value.readingOrder.firstOrNull()?.let(opened.value::locatorFromLink)
                        ?.copyWithLocations(
                            position = pg,
                            totalProgression = remote?.percent ?: local?.locations?.totalProgression,
                        )
                } ?: local
                _state.value = PdfReaderState.Ready(
                    publication = opened.value,
                    initialLocator = resumeLocator,
                    title = detail?.summary?.title ?: downloadTitle ?: "",
                    pageCount = (opened.value.metadata.numberOfPages
                        ?: opened.value.readingOrder.size).coerceAtLeast(1),
                )
                locatorStore.noteOpened(
                    serverId = route.serverId,
                    bookId = route.bookId,
                    title = detail?.summary?.title ?: downloadTitle,
                    author = detail?.summary?.authorLine,
                    coverUrl = detail?.summary?.coverUrl,
                    format = ContentFormat.PDF,
                    digestUrl = remoteHref,
                )
            }
            is Outcome.Failure ->
                _state.value = PdfReaderState.Error(opened.error.message ?: "Couldn't open this PDF")
        }
    }

    fun onLocatorChanged(locator: Locator) {
        locatorUpdates.tryEmit(locator)
    }

    override fun onCleared() {
        runCatching { publication?.close() }
        publication = null
    }
}
