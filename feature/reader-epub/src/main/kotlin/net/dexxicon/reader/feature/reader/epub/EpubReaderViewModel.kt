package net.dexxicon.reader.feature.reader.epub

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.HighlightRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.sync.DigestSource
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.HighlightColor
import net.dexxicon.reader.core.reader.PublicationStreamer
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderLocatorStore
import net.dexxicon.reader.core.reader.ReaderPreferencesStore

import net.dexxicon.reader.feature.reader.epub.navigation.EpubReaderRoute
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.math.abs
import javax.inject.Inject

sealed interface EpubReaderState {
    data object Loading : EpubReaderState
    data class Error(val message: String) : EpubReaderState
    data class Ready(
        val publication: Publication,
        val initialLocator: Locator?,
        val title: String,
        /** A newer position from KOReader sync / another device, if any (0.0–1.0). */
        val remoteResumePercent: Double? = null,
        val remoteResumeLocator: Locator? = null,
    ) : EpubReaderState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val locatorStore: ReaderLocatorStore,
    private val downloadRepository: DownloadRepository,
    private val streamer: PublicationStreamer,
    private val progressRepository: ReadingProgressRepository,
    private val highlightRepository: HighlightRepository,
    preferencesStore: ReaderPreferencesStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EpubReaderRoute>()
    private var digestSource: DigestSource? = null

    val highlights: StateFlow<List<Highlight>> =
        highlightRepository.observe(route.serverId, route.bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<EpubReaderState>(EpubReaderState.Loading)
    val state: StateFlow<EpubReaderState> = _state.asStateFlow()

    val preferences: StateFlow<ReaderDisplayPreferences> = preferencesStore.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderDisplayPreferences())

    private val locatorUpdates = MutableSharedFlow<Locator>(extraBufferCapacity = 1)
    private var publication: Publication? = null

    val updatePreferences: (suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit) =
        preferencesStore::update

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            runCatching { highlightRepository.syncFromServer(route.serverId, route.bookId) }
        }
        viewModelScope.launch {
            // locatorStore.save persists the position and pushes it to KOReader sync.
            locatorUpdates.debounce(1_500).collect { locator ->
                locatorStore.save(route.serverId, route.bookId, locator)
            }
        }
    }

    private suspend fun load() {
        // Prefer a downloaded copy — this is also what makes the reader work offline, so
        // don't gate it on the (network) catalog detail call.
        val localFile = downloadRepository.localFile(route.serverId, route.bookId)
        val downloadTitle = downloadRepository.get(route.serverId, route.bookId)?.title

        val detail = (catalogRepository.detail(route.serverId, route.bookId) as? Outcome.Success)?.value

        if (localFile == null && detail == null) {
            _state.value = EpubReaderState.Error("Couldn't reach this book — download it for offline reading")
            return
        }

        // The remote acquisition href — kept even when opening a downloaded copy, so native
        // (web-reader) progress sync still has the file reference.
        val remoteHref = detail?.acquisitions?.firstOrNull { it.format == ContentFormat.EPUB }?.href
            ?: detail?.primaryAcquisition?.href

        val opened = if (localFile != null) {
            digestSource = DigestSource.LocalFile(localFile)
            streamer.open(localFile, MediaType.EPUB)
        } else {
            val acquisition = detail!!.acquisitions.firstOrNull { it.format == ContentFormat.EPUB }
                ?: detail.primaryAcquisition
            if (acquisition == null || acquisition.format != ContentFormat.EPUB) {
                _state.value = EpubReaderState.Error("This book isn't an EPUB")
                return
            }
            digestSource = DigestSource.Remote(acquisition.href)
            streamer.open(acquisition.href, MediaType.EPUB)
        }

        when (opened) {
            is Outcome.Success -> {
                publication = opened.value
                val initial = locatorStore.initialLocator(route.serverId, route.bookId)
                val remote = progressRepository.remoteResumePercent(
                    route.serverId, route.bookId, ContentFormat.EPUB, remoteHref,
                    initial?.locations?.totalProgression,
                )
                val remoteLocator = remote?.let { target ->
                    runCatching {
                        opened.value.positions().minByOrNull {
                            abs((it.locations.totalProgression ?: 0.0) - target)
                        }
                    }.getOrNull()
                }
                _state.value = EpubReaderState.Ready(
                    publication = opened.value,
                    initialLocator = initial,
                    title = detail?.summary?.title ?: downloadTitle ?: "",
                    remoteResumePercent = remote,
                    remoteResumeLocator = remoteLocator,
                )
                locatorStore.noteOpened(
                    serverId = route.serverId,
                    bookId = route.bookId,
                    title = detail?.summary?.title ?: downloadTitle,
                    author = detail?.summary?.authorLine,
                    coverUrl = detail?.summary?.coverUrl,
                    format = ContentFormat.EPUB,
                    digestUrl = remoteHref ?: (digestSource as? DigestSource.Remote)?.url,
                )
            }
            is Outcome.Failure ->
                _state.value = EpubReaderState.Error(opened.error.message ?: "Couldn't open this EPUB")
        }
    }

    fun onLocatorChanged(locator: Locator) {
        locatorUpdates.tryEmit(locator)
    }

    fun addHighlight(locator: Locator) {
        val text = locator.text.highlight ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            highlightRepository.add(
                serverId = route.serverId,
                bookId = route.bookId,
                locatorJson = locator.toJSON().toString(),
                progression = locator.locations.totalProgression ?: 0.0,
                text = text.trim(),
                color = HighlightColor.YELLOW,
                chapterTitle = locator.title,
            )
        }
    }

    fun setNote(id: String, note: String?) {
        viewModelScope.launch { highlightRepository.updateNote(id, note?.takeIf { it.isNotBlank() }) }
    }

    fun setColor(id: String, color: HighlightColor) {
        viewModelScope.launch { highlightRepository.updateColor(id, color) }
    }

    fun deleteHighlight(id: String) {
        viewModelScope.launch { highlightRepository.delete(id) }
    }

    override fun onCleared() {
        runCatching { publication?.close() }
        publication = null
    }
}
