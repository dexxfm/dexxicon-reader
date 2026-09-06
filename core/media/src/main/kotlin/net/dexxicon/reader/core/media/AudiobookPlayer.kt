package net.dexxicon.reader.core.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.model.Audiobook
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerUiState(
    val audiobook: Audiobook? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    /** Epoch millis at which playback will pause, or null. */
    val sleepTimerEndsAt: Long? = null,
) {
    val currentChapterIndex: Int get() = audiobook?.chapterIndexAt(positionMs) ?: 0
    val currentChapterTitle: String? get() = audiobook?.chapterAt(positionMs)?.title
}

@Singleton
class AudiobookPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val progressSink: PlaybackProgressSink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    private var current: Audiobook? = null

    private fun withController(block: (MediaController) -> Unit) {
        val existing = controller
        if (existing != null) {
            block(existing)
            return
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(playerListener)
            block(c)
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _state.value = _state.value.copy(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.coerceAtLeast(0L),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                speed = player.playbackParameters.speed,
            )
            if (player.isPlaying) startPolling() else stopPolling()
        }
    }

    fun play(audiobook: Audiobook, startPositionMs: Long) {
        current = audiobook
        _state.value = PlayerUiState(
            audiobook = audiobook,
            durationMs = audiobook.durationMs,
            positionMs = startPositionMs,
        )
        withController { c ->
            val item = MediaItem.Builder()
                .setUri(audiobook.streamUrl)
                .setMediaId(audiobook.key)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(audiobook.title)
                        .setArtist(audiobook.author)
                        .setArtworkUri(audiobook.coverUrl?.let(android.net.Uri::parse))
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
            c.setMediaItem(item, startPositionMs)
            c.prepare()
            c.play()
        }
    }

    /** True if the player already holds [key]'s audiobook (so the UI can just re-attach). */
    fun isLoaded(key: String): Boolean = current?.key == key

    fun playPause() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    fun skipForward() = withController { it.seekTo(it.currentPosition + 30_000) }
    fun skipBack() = withController { it.seekTo((it.currentPosition - 15_000).coerceAtLeast(0L)) }

    fun seekToChapter(index: Int) = withController { c ->
        current?.chapters?.getOrNull(index)?.let { c.seekTo(it.startMs) }
    }

    fun nextChapter() {
        val a = current ?: return
        seekToChapter((a.chapterIndexAt(_state.value.positionMs) + 1).coerceAtMost(a.chapters.lastIndex))
    }

    fun previousChapter() {
        val a = current ?: return
        val idx = a.chapterIndexAt(_state.value.positionMs)
        val target = if (_state.value.positionMs - (a.chapters.getOrNull(idx)?.startMs ?: 0L) > 3_000) idx else idx - 1
        seekToChapter(target.coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    fun setSleepTimer(durationMs: Long?) {
        sleepJob?.cancel()
        if (durationMs == null) {
            _state.value = _state.value.copy(sleepTimerEndsAt = null)
            return
        }
        val endsAt = System.currentTimeMillis() + durationMs
        _state.value = _state.value.copy(sleepTimerEndsAt = endsAt)
        sleepJob = scope.launch {
            delay(durationMs)
            withController { it.pause() }
            _state.value = _state.value.copy(sleepTimerEndsAt = null)
        }
    }

    fun stop() {
        pollJob?.cancel()
        sleepJob?.cancel()
        controller?.let {
            persistPosition(it.currentPosition)
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        current = null
        _state.value = PlayerUiState()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var sinceSave = 0L
            while (true) {
                val c = controller ?: break
                val pos = c.currentPosition.coerceAtLeast(0L)
                _state.value = _state.value.copy(
                    positionMs = pos,
                    durationMs = c.duration.coerceAtLeast(current?.durationMs ?: 0L),
                )
                sinceSave += 1000
                if (sinceSave >= 10_000) {
                    persistPosition(pos)
                    sinceSave = 0
                }
                delay(1_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        controller?.let { persistPosition(it.currentPosition) }
    }

    private fun persistPosition(positionMs: Long) {
        val a = current ?: return
        if (positionMs <= 0L) return
        scope.launch {
            progressSink.save(
                serverId = a.serverId,
                bookId = a.bookId,
                positionMs = positionMs,
                percent = a.durationMs.takeIf { it > 0 }?.let { positionMs.toDouble() / it },
            )
        }
    }
}
