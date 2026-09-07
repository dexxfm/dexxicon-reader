package net.dexxicon.reader.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Persists the listening position straight from the playback service, so it is saved (and
 * pushed to the server) no matter who is driving playback — the in-app player, the media
 * notification, or Android Auto with no app UI in the picture at all.
 *
 * The book is identified by the current [androidx.media3.common.MediaItem]'s `mediaId`,
 * which is always `"serverId::bookId"`.
 */
internal class ServicePositionWriter(
    private val player: Player,
    private val sink: PlaybackProgressSink,
    private val scope: CoroutineScope,
) {
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicking() else stopTickingAndSave()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // Save wherever we left the previous item before the metadata swaps.
            save()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) save()
        }
    }

    fun attach() {
        player.addListener(listener)
        if (player.isPlaying) startTicking()
    }

    fun detach() {
        stopTickingAndSave()
        player.removeListener(listener)
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                save()
            }
        }
    }

    private fun stopTickingAndSave() {
        ticker?.cancel()
        ticker = null
        save()
    }

    /** Reads the player on the caller's (application) thread, then persists off it. */
    private fun save() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val parts = mediaId.split("::", limit = 2).takeIf { it.size == 2 } ?: return
        val (serverId, bookId) = parts
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        if (positionMs <= 0L) return
        val percent = player.duration.takeIf { it > 0L }?.let { positionMs.toDouble() / it }
        scope.launch { sink.save(serverId, bookId, positionMs, percent) }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
