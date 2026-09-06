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
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.feature.reader.epub.navigation.EpubReaderRoute
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import javax.inject.Inject

sealed interface EpubReaderState {
    data object Loading : EpubReaderState
    data class Error(val message: String) : EpubReaderState
    data class Ready(
        val publication: Publication,
        val initialLocator: Locator?,
        val title: String,
    ) : EpubReaderState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val progressRepository: ReadingProgressRepository,
    private val opener: EpubPublicationOpener,
    preferencesStore: ReaderPreferencesStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<EpubReaderRoute>()

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
            locatorUpdates.debounce(1_500).collect { persist(it) }
        }
    }

    private suspend fun load() {
        val detail = when (val outcome = catalogRepository.detail(route.serverId, route.bookId)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> {
                _state.value = EpubReaderState.Error(outcome.error.message ?: "Couldn't load this book")
                return
            }
        }

        val acquisition = detail.acquisitions.firstOrNull { it.format == ContentFormat.EPUB }
            ?: detail.primaryAcquisition
        if (acquisition == null || acquisition.format != ContentFormat.EPUB) {
            _state.value = EpubReaderState.Error("This book isn't an EPUB")
            return
        }

        when (val opened = opener.open(acquisition.href)) {
            is Outcome.Success -> {
                publication = opened.value
                val initial = progressRepository.get(route.serverId, route.bookId)
                    ?.locator
                    ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                _state.value = EpubReaderState.Ready(
                    publication = opened.value,
                    initialLocator = initial,
                    title = detail.summary.title,
                )
            }
            is Outcome.Failure ->
                _state.value = EpubReaderState.Error(opened.error.message ?: "Couldn't open this EPUB")
        }
    }

    fun onLocatorChanged(locator: Locator) {
        locatorUpdates.tryEmit(locator)
    }

    private suspend fun persist(locator: Locator) {
        progressRepository.save(
            ReadingProgress(
                serverId = route.serverId,
                bookId = route.bookId,
                percent = locator.locations.totalProgression,
                locator = locator.toJSON().toString(),
            ),
        )
    }

    override fun onCleared() {
        runCatching { publication?.close() }
        publication = null
    }
}
