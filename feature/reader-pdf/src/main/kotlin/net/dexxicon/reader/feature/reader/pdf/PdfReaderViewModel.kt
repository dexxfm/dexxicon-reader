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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.reader.PublicationStreamer
import net.dexxicon.reader.core.reader.ReaderLocatorStore
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PdfReaderRoute>()

    private val _state = MutableStateFlow<PdfReaderState>(PdfReaderState.Loading)
    val state: StateFlow<PdfReaderState> = _state.asStateFlow()

    private val locatorUpdates = MutableSharedFlow<Locator>(extraBufferCapacity = 1)
    private var publication: Publication? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            locatorUpdates.debounce(1_000).collect {
                locatorStore.save(route.serverId, route.bookId, it)
            }
        }
    }

    private suspend fun load() {
        val localFile = downloadRepository.localFile(route.serverId, route.bookId)
        val downloadTitle = downloadRepository.get(route.serverId, route.bookId)?.title
        val detail = (catalogRepository.detail(route.serverId, route.bookId) as? Outcome.Success)?.value

        if (localFile == null && detail == null) {
            _state.value = PdfReaderState.Error("Couldn't reach this PDF — download it for offline reading")
            return
        }

        var remoteHref: String? = null
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
                _state.value = PdfReaderState.Ready(
                    publication = opened.value,
                    initialLocator = locatorStore.initialLocator(route.serverId, route.bookId),
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
