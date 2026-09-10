package net.dexxicon.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.crash.DiagnosticsArchive
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.datastore.PlayerPreferences
import net.dexxicon.reader.core.datastore.PlayerPreferencesStore
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderPreferencesStore
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: AppPreferencesStore,
    private val readerStore: ReaderPreferencesStore,
    private val playerStore: PlayerPreferencesStore,
    private val diagnosticsArchive: DiagnosticsArchive,
    downloadRepository: DownloadRepository,
) : ViewModel() {

    /** Zip of all app logs to attach to a problem report. Null if it can't be built. */
    suspend fun buildLogArchive(): File? = withContext(Dispatchers.IO) { diagnosticsArchive.build() }

    val preferences: StateFlow<AppPreferences> = store.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    /** Reader look & feel — shared by EPUB, PDF and comic, same store each reader's own
     * settings sheet reads and writes. Book Defaults' Books/Comics/PDFs sub-screens each
     * show the slice of this that reader actually uses. */
    val readerPreferences: StateFlow<ReaderDisplayPreferences> = readerStore.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderDisplayPreferences())

    fun updateReaderPreferences(transform: (ReaderDisplayPreferences) -> ReaderDisplayPreferences) {
        viewModelScope.launch { readerStore.update(transform) }
    }

    /** Skip silence + default starting speed for the audiobook player. Same store the
     * player's own Audio options sheet reads and writes — a change from either place
     * shows up in both. */
    val playerPreferences: StateFlow<PlayerPreferences> = playerStore.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPreferences())

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { playerStore.setSkipSilence(enabled) }
    }

    fun setDefaultSpeed(speed: Float) {
        viewModelScope.launch { playerStore.setDefaultSpeed(speed) }
    }

    /** Bytes currently held by downloaded media. */
    val downloadUsedBytes: StateFlow<Long> = downloadRepository.usedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { store.setTheme(theme) }
    }

    fun setDownloadsWifiOnly(enabled: Boolean) {
        viewModelScope.launch { store.setDownloadsWifiOnly(enabled) }
    }

    fun setBookViewDefault(mode: BookViewMode) {
        viewModelScope.launch { store.setBookViewDefault(mode) }
    }

    /** [bytes] null = no limit. */
    fun setDownloadLimit(bytes: Long?) {
        viewModelScope.launch { store.setDownloadLimit(bytes) }
    }
}
