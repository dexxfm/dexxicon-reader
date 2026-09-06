package net.dexxicon.reader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.Download
import javax.inject.Inject

data class LibraryUiState(
    val downloads: List<Download> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = downloadRepository.downloads
        .map { LibraryUiState(downloads = it, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun remove(download: Download) {
        viewModelScope.launch { downloadRepository.remove(download.serverId, download.bookId) }
    }
}
