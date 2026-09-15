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
import net.dexxicon.reader.core.model.ResumeConflict
import net.dexxicon.reader.feature.player.navigation.PlayerRoute
import org.json.JSONObject
import javax.inject.Inject
import kotlin.math.abs

data class PlayerScreenState(
    val loading: Boolean = true,
    val error: String? = null,
    val resumeConflict: ResumeConflict? = null,
)

/** issue #216 — below this, a discrepancy is normal drift (save-interval granularity, a
 *  seek that landed a beat before the last periodic save), not a real conflict worth
 *  interrupting playback to ask about. */
private const val RESUME_CONFLICT_TOLERANCE_MS = 5_000L

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
            // issue #216 — this is exactly the scenario the bug lived in: a session already
            // sitting in the background, never re-checked against the server just because
            // the reader screen was reopened. Only worth checking while paused — interrupting
            // audio that's actively playing to ask about a conflict would be its own bug.
            if (!player.state.value.isPlaying) {
                checkForConflictOnAlreadyLoaded()
            }
            _screen.value = _screen.value.copy(loading = false)
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
                    narrator = detail.narratorLine.takeIf { it.isNotBlank() },
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
                // issue #216 — the raw server position, unblended, so a genuine conflict (the
                // server was reset elsewhere) can be surfaced instead of "furthest wins"
                // silently keeping whichever side happens to have the bigger number.
                val remoteMs = progressRepository.nativeAudiobookPositionMs(
                    serverId = route.serverId,
                    bookId = route.bookId,
                    digestUrl = acquisition.href,
                    durationMs = durationMs,
                )
                if (remoteMs != null && abs(remoteMs - localMs) > RESUME_CONFLICT_TOLERANCE_MS) {
                    player.prepare(audiobook, localMs)
                    _screen.value = PlayerScreenState(
                        loading = false,
                        resumeConflict = ResumeConflict(remoteMs, localMs, durationMs),
                    )
                } else {
                    // remoteMs is already the answer for a native server (just pulled above,
                    // agrees with local within tolerance) — only re-resolve when it's null
                    // (a kosync server, never pulled above, or the native pull itself failed).
                    val startMs = remoteMs ?: progressRepository.audiobookResumeMs(
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
    }

    /** issue #216 — for a book that's already loaded (paused) in the background player: no
     *  fresh [Audiobook]/acquisition to fetch, just compare positions and, on a conflict,
     *  surface the same dialog without touching playback until the user decides. */
    private suspend fun checkForConflictOnAlreadyLoaded() {
        val loaded = player.state.value.audiobook ?: return
        val localMs = player.state.value.positionMs
        val remoteMs = progressRepository.nativeAudiobookPositionMs(
            serverId = route.serverId,
            bookId = route.bookId,
            digestUrl = loaded.streamUrl,
            durationMs = loaded.durationMs,
        ) ?: return
        if (abs(remoteMs - localMs) > RESUME_CONFLICT_TOLERANCE_MS) {
            _screen.value = _screen.value.copy(resumeConflict = ResumeConflict(remoteMs, localMs, loaded.durationMs))
        }
    }

    /** issue #216 — [useServerPosition] true seeks to the server's position and plays;
     *  false keeps wherever the player already is, plays, and pushes that position to the
     *  server so it becomes authoritative (the dirty-flag path from #214) instead of leaving
     *  the stale conflict to resurface on the next sync. */
    fun resolveResumeConflict(useServerPosition: Boolean) {
        val conflict = _screen.value.resumeConflict ?: return
        _screen.value = _screen.value.copy(resumeConflict = null)
        if (useServerPosition) {
            player.seekAndPlay(conflict.serverPositionMs)
        } else {
            player.resume()
            viewModelScope.launch {
                progressRepository.save(
                    ReadingProgress(
                        serverId = route.serverId,
                        bookId = route.bookId,
                        percent = conflict.durationMs.takeIf { it > 0L }
                            ?.let { (conflict.localPositionMs.toDouble() / it).coerceIn(0.0, 1.0) },
                        locator = "{\"position\":${conflict.localPositionMs}}",
                    ),
                )
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
