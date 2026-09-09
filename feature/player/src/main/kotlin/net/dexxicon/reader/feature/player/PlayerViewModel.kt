package net.dexxicon.reader.feature.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.datastore.PlayerPreferencesStore
import net.dexxicon.reader.core.media.AudiobookPlayer
import net.dexxicon.reader.core.media.PlayerUiState
import net.dexxicon.reader.core.model.Audiobook
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.feature.player.navigation.PlayerRoute
import org.json.JSONObject
import javax.inject.Inject

data class PlayerScreenState(
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val progressRepository: ReadingProgressRepository,
    private val playerPreferences: PlayerPreferencesStore,
    val player: AudiobookPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PlayerRoute>()

    private val _screen = MutableStateFlow(PlayerScreenState())
    val screen: StateFlow<PlayerScreenState> = _screen.asStateFlow()

    val playback: StateFlow<PlayerUiState> = player.state

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val key = "${route.serverId}::${route.bookId}"
        if (player.isLoaded(key)) {
            _screen.value = PlayerScreenState(loading = false)
            return
        }

        when (val result = catalogRepository.detail(route.serverId, route.bookId)) {
            is Outcome.Failure ->
                _screen.value = PlayerScreenState(loading = false, error = result.error.message ?: "Couldn't load this audiobook")

            is Outcome.Success -> {
                val detail = result.value
                val acquisition = detail.acquisitions.firstOrNull { it.format == ContentFormat.AUDIOBOOK }
                    ?: detail.primaryAcquisition
                if (acquisition == null) {
                    _screen.value = PlayerScreenState(loading = false, error = "This book has no audio to play")
                    return
                }
                val durationMs = detail.audio?.durationMs ?: 0L
                val audiobook = Audiobook(
                    serverId = route.serverId,
                    bookId = route.bookId,
                    title = detail.summary.title,
                    author = detail.summary.authorLine,
                    coverUrl = detail.summary.coverUrl,
                    streamUrl = acquisition.href,
                    durationMs = durationMs,
                    chapters = detail.audio?.chapters.orEmpty(),
                )
                progressRepository.save(
                    ReadingProgress(
                        serverId = route.serverId,
                        bookId = route.bookId,
                        title = detail.summary.title,
                        author = detail.summary.authorLine,
                        coverUrl = detail.summary.coverUrl,
                        format = ContentFormat.AUDIOBOOK,
                        digestUrl = acquisition.href,
                    ),
                )
                val localMs = progressRepository.get(route.serverId, route.bookId)
                    ?.locator
                    ?.let { runCatching { JSONObject(it).optLong("position", 0L) }.getOrDefault(0L) }
                    ?: 0L
                val startMs = progressRepository.audiobookResumeMs(
                    serverId = route.serverId,
                    bookId = route.bookId,
                    digestUrl = acquisition.href,
                    durationMs = durationMs,
                    localMs = localMs,
                )
                player.play(audiobook, startMs)
                _screen.value = PlayerScreenState(loading = false)
            }
        }
    }

    fun playPause() = player.playPause()
    fun skipForward() = player.skipForward()
    fun skipBack() = player.skipBack()
    fun nextChapter() = player.nextChapter()
    fun previousChapter() = player.previousChapter()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun seekToChapter(index: Int) = player.seekToChapter(index)
    fun setSpeed(speed: Float) = player.setSpeed(speed)
    fun setAudioOutput(deviceId: Int?) = player.setAudioOutput(deviceId)
    fun setSleepTimer(durationMs: Long?) = player.setSleepTimer(durationMs)
    fun setSleepTimerEndOfChapter() = player.setSleepTimerEndOfChapter()

    fun setSkipSilence(enabled: Boolean) =
        viewModelScope.launch { playerPreferences.setSkipSilence(enabled) }

    fun setSkipForwardSeconds(seconds: Int) =
        viewModelScope.launch { playerPreferences.setSkipForwardSeconds(seconds) }

    fun setSkipBackSeconds(seconds: Int) =
        viewModelScope.launch { playerPreferences.setSkipBackSeconds(seconds) }

    fun setSmartRewindSeconds(seconds: Int) =
        viewModelScope.launch { playerPreferences.setSmartRewindSeconds(seconds) }
}
