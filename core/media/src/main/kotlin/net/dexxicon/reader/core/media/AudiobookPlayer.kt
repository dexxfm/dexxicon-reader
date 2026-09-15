package net.dexxicon.reader.core.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
import net.dexxicon.reader.core.datastore.PlayerPreferences
import net.dexxicon.reader.core.datastore.PlayerPreferencesStore
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
    /** Playback will pause when the current chapter ends. */
    val sleepAtChapterEnd: Boolean = false,
    /** `AudioDeviceInfo.id` playback is pinned to, or null for the system default route. */
    val preferredAudioDeviceId: Int? = null,
    val options: PlayerPreferences = PlayerPreferences(),
    /** A human-readable message for the most recent playback failure — an ExoPlayer-reported
     *  error (bad stream URL, network failure, unsupported format, …) or a failure to even
     *  connect to [PlaybackService] — or null. Before this, both failure modes were entirely
     *  silent: [play] sets [audiobook]/title/cover synchronously, before the real
     *  [MediaController] connection or the actual stream request ever resolves, so the player
     *  screen looked fully loaded — cover, title, transport controls — with a Play button that
     *  simply did nothing forever (issue #190). Cleared automatically by the next [play]. */
    val error: String? = null,
) {
    val currentChapterIndex: Int get() = audiobook?.chapterIndexAt(positionMs) ?: 0
    val currentChapterTitle: String? get() = audiobook?.chapterAt(positionMs)?.title
}

@Singleton
class AudiobookPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerPreferences: PlayerPreferencesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    /** Non-null exactly while a [MediaController] connection is in flight — [withController]
     *  checks this so a burst of commands (issue #190's own reproduction: mash Play a few
     *  times while the very first connection is still resolving) queues onto [pendingCommands]
     *  instead of kicking off a redundant `buildAsync()` per call. */
    private var connectingFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val pendingCommands = mutableListOf<(MediaController) -> Unit>()
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    private var current: Audiobook? = null
    private var options: PlayerPreferences = PlayerPreferences()

    /** Survives a new [play] so a chosen output isn't silently lost between books. */
    private var preferredDeviceId: Int? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * When a Bluetooth headset/speaker or an Android Auto / car output connects, move
     * playback onto it — even if the user had pinned the phone speaker. A pin only lasts
     * until the next output device change.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            added?.firstOrNull { it.isFollowable() }?.let { setAudioOutput(it.id) }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            if (removed?.any { it.id == preferredDeviceId } == true) setAudioOutput(null)
        }
    }

    private fun AudioDeviceInfo.isFollowable(): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BUS, // Android Automotive / car head unit
    )

    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        scope.launch {
            playerPreferences.preferences.collect { prefs ->
                options = prefs
                _state.value = _state.value.copy(options = prefs)
                applySkipSilence(prefs.skipSilence)
            }
        }
    }

    /**
     * Runs [block] against a connected [MediaController], connecting first if needed. issue
     * #190: previously this started a brand-new `buildAsync()` on *every* call made while
     * [controller] was still null, with no de-duplication — usually harmless, but if that
     * first connection ever failed or hung (a stuck [PlaybackService] bind, a `SecurityException`
     * from a missing runtime permission, …) every command just piled up as its own silent,
     * never-resolving attempt: no error, no timeout, nothing for the UI to show. Now only the
     * first call actually connects; later calls made before it resolves queue onto
     * [pendingCommands] instead, and a connection failure is logged and surfaced via [_state]'s
     * [PlayerUiState.error] rather than disappearing.
     */
    private fun withController(block: (MediaController) -> Unit) {
        val existing = controller
        if (existing != null) {
            block(existing)
            return
        }
        pendingCommands.add(block)
        if (connectingFuture != null) return

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connectingFuture = future
        future.addListener({
            connectingFuture = null
            val queued = pendingCommands.toList()
            pendingCommands.clear()
            runCatching { future.get() }
                .onSuccess { c ->
                    controller = c
                    c.addListener(playerListener)
                    queued.forEach { it(c) }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to connect to PlaybackService", e)
                    _state.value = _state.value.copy(error = "Couldn't start the player — try again.")
                }
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

        /** issue #190 — previously unhandled entirely, so a failed stream (bad/expired auth on
         *  [Audiobook.streamUrl], a network error, an unsupported format, …) left the player
         *  screen showing a fully "loaded" book — cover, title, transport controls all present,
         *  since [play] sets those synchronously — with a Play button that silently did
         *  nothing. */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error for ${current?.title}: ${error.errorCodeName}", error)
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = false,
                error = playbackErrorMessage(error),
            )
        }
    }

    fun play(audiobook: Audiobook, startPositionMs: Long) = load(audiobook, startPositionMs, autoplay = true)

    /** issue #216 — loads and seeks exactly like [play], but leaves playback paused: for
     *  when the caller first needs the user to resolve a resume-position conflict (the
     *  server and the locally-known position disagree) rather than silently picking one. */
    fun prepare(audiobook: Audiobook, startPositionMs: Long) = load(audiobook, startPositionMs, autoplay = false)

    private fun load(audiobook: Audiobook, startPositionMs: Long, autoplay: Boolean) {
        current = audiobook
        _state.value = PlayerUiState(
            audiobook = audiobook,
            durationMs = audiobook.durationMs,
            positionMs = startPositionMs,
            preferredAudioDeviceId = preferredDeviceId,
            // Rebuilding the whole state here used to default `options` back to
            // PlayerPreferences() — the audio options sheet (skip silence, skip
            // intervals…) would flash back to its defaults on every open/re-open even
            // though the persisted preference (and what actually got applied to the
            // player below) hadn't changed. Carry the last-collected preferences over.
            options = options,
            // Shown immediately, before the player's own onEvents callback would confirm
            // it — otherwise the label reads "1.0×" for a beat even though the book is
            // about to start at the user's chosen default.
            speed = options.defaultSpeed,
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
                        // Without this, ChapterMetadataUpdater never sees this book's
                        // chapters (its extras stay empty) — Android Auto's now-playing
                        // card shows the author forever instead of the current chapter.
                        .setExtras(Bundle().putChapters(audiobook.chapters))
                        .build(),
                )
                .build()
            c.setMediaItem(item, startPositionMs)
            // ExoPlayer's playback speed is a player-level setting, not per media item —
            // left alone it carries over from whatever book played before this one. Start
            // every book at the user's default speed instead (Settings › Audiobooks, or
            // wherever they last set it — see setSpeed()).
            c.setPlaybackSpeed(options.defaultSpeed)
            c.prepare()
            if (autoplay) c.play()
            applySkipSilence(options.skipSilence)
            if (preferredDeviceId != null) applyAudioOutput(preferredDeviceId)
        }
    }

    /** issue #216 — starts playback of whatever's already loaded, without touching position:
     *  resolves a resume conflict by keeping the current position. */
    fun resume() = withController { it.play() }

    /** issue #216 — seeks then starts playback: resolves a resume conflict by accepting the
     *  server's position. */
    fun seekAndPlay(positionMs: Long) = withController { c ->
        c.seekTo(positionMs.coerceAtLeast(0L))
        c.play()
    }

    private fun applySkipSilence(enabled: Boolean) = withController { c ->
        runCatching {
            c.sendCustomCommand(
                SessionCommand(PlaybackCommands.SET_SKIP_SILENCE, Bundle.EMPTY),
                Bundle().apply { putBoolean(PlaybackCommands.ARG_ENABLED, enabled) },
            )
        }
    }

    /** Pins playback to [deviceId] (`AudioDeviceInfo.id`), or null for the default route. */
    fun setAudioOutput(deviceId: Int?) {
        preferredDeviceId = deviceId
        _state.value = _state.value.copy(preferredAudioDeviceId = deviceId)
        applyAudioOutput(deviceId)
    }

    private fun applyAudioOutput(deviceId: Int?) = withController { c ->
        runCatching {
            c.sendCustomCommand(
                SessionCommand(PlaybackCommands.SET_AUDIO_OUTPUT, Bundle.EMPTY),
                Bundle().apply { putInt(PlaybackCommands.ARG_DEVICE_ID, deviceId ?: -1) },
            )
        }
    }

    /** True if the player already holds [key]'s audiobook (so the UI can just re-attach). */
    fun isLoaded(key: String): Boolean = current?.key == key

    fun playPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            val rewind = options.smartRewindSeconds * 1000L
            if (rewind > 0L) c.seekTo((c.currentPosition - rewind).coerceAtLeast(0L))
            c.play()
        }
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    fun skipForward() = withController {
        it.seekTo(it.currentPosition + options.skipForwardSeconds * 1000L)
    }
    fun skipBack() = withController {
        it.seekTo((it.currentPosition - options.skipBackSeconds * 1000L).coerceAtLeast(0L))
    }

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

    /**
     * Changes the current book's speed and — same knob as Settings › Audiobooks —
     * remembers it as the starting speed for every book you open from here on.
     */
    fun setSpeed(speed: Float) {
        withController { it.setPlaybackSpeed(speed) }
        scope.launch { playerPreferences.setDefaultSpeed(speed) }
    }

    fun setSleepTimer(durationMs: Long?) {
        sleepJob?.cancel()
        if (durationMs == null) {
            _state.value = _state.value.copy(sleepTimerEndsAt = null, sleepAtChapterEnd = false)
            return
        }
        val endsAt = System.currentTimeMillis() + durationMs
        _state.value = _state.value.copy(sleepTimerEndsAt = endsAt, sleepAtChapterEnd = false)
        sleepJob = scope.launch {
            delay(durationMs)
            withController { it.pause() }
            _state.value = _state.value.copy(sleepTimerEndsAt = null)
        }
    }

    /** Pause when the current chapter finishes. */
    fun setSleepTimerEndOfChapter() {
        sleepJob?.cancel()
        _state.value = _state.value.copy(sleepTimerEndsAt = null, sleepAtChapterEnd = true)
    }

    fun clearSleepTimer() = setSleepTimer(null)

    private fun currentChapterEndMs(positionMs: Long): Long? {
        val a = current ?: return null
        if (a.chapters.isEmpty()) return null
        val idx = a.chapterIndexAt(positionMs)
        val next = a.chapters.getOrNull(idx + 1)?.startMs
        return next ?: a.durationMs.takeIf { it > 0 }
    }

    fun stop() {
        pollJob?.cancel()
        sleepJob?.cancel()
        controller?.let {
            // Releasing the controller only disconnects it — the service keeps playing.
            // Stop and clear so the "X" on the mini-player actually ends playback and drops
            // the media notification.
            it.stop()
            it.clearMediaItems()
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        current = null
        _state.value = PlayerUiState()
    }

    /**
     * Drives the UI scrubber and the end-of-chapter sleep timer while playing. The listening
     * position is persisted by the playback service ([ServicePositionWriter]) so it is saved
     * regardless of whether this in-app player is attached.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val c = controller ?: break
                val pos = c.currentPosition.coerceAtLeast(0L)
                _state.value = _state.value.copy(
                    positionMs = pos,
                    durationMs = c.duration.coerceAtLeast(current?.durationMs ?: 0L),
                )
                if (_state.value.sleepAtChapterEnd) {
                    val end = currentChapterEndMs(pos)
                    if (end != null && pos >= end - 800) {
                        c.pause()
                        _state.value = _state.value.copy(sleepAtChapterEnd = false)
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    companion object {
        private const val TAG = "AudiobookPlayer"
    }
}

/** A short, user-facing message for an ExoPlayer-reported playback failure — issue #190. Errors
 *  reading the actual audio stream (a rejected/expired auth header, a 404, an unreachable host)
 *  are by far the most likely cause in this app, since [Audiobook.streamUrl] always points at
 *  the server's own authenticated download endpoint. */
private fun playbackErrorMessage(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    -> "Couldn't reach the server — check your connection and try again."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    -> "This audiobook's file couldn't be found on the server."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> "This audiobook's file format isn't supported."
    else -> "Playback failed — try again."
}
