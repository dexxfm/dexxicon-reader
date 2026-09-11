package net.dexxicon.reader.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.model.Chapter

/**
 * Keeps the now-playing [MediaItem]'s subtitle in step with the current chapter, so Android
 * Auto / Automotive — and the media notification / lock screen, which read the same
 * metadata — show "Chapter 5: The Reckoning" instead of just the book title, for any
 * audiobook that has chapter markers. Chapters travel with the item as extras (packed by
 * [putChapters] wherever the item is built — see `PlaybackService.playableItem`), so this
 * needs no second lookup of its own.
 *
 * [Player.replaceMediaItem] swaps the item's metadata without interrupting playback or
 * restarting the current position — that's what keeps this update seamless.
 */
internal class ChapterMetadataUpdater(
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private var ticker: Job? = null
    private var currentMediaId: String? = null
    private var chapters: List<Chapter> = emptyList()
    private var appliedChapterIndex: Int = -1

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // A same-id transition is our own replaceMediaItem() call below, not a new book —
            // ignore it, or we'd re-read (unchanged) chapters and immediately reapply.
            val id = item?.mediaId
            if (id == currentMediaId) return
            currentMediaId = id
            chapters = item?.mediaMetadata?.extras.getChapters()
            appliedChapterIndex = -1
            applyIfNeeded()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = applyIfNeeded()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicking() else stopTicking()
        }
    }

    fun attach() {
        player.addListener(listener)
        currentMediaId = player.currentMediaItem?.mediaId
        chapters = player.currentMediaItem?.mediaMetadata?.extras.getChapters()
        appliedChapterIndex = -1
        applyIfNeeded()
        if (player.isPlaying) startTicking()
    }

    fun detach() {
        stopTicking()
        player.removeListener(listener)
    }

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                applyIfNeeded()
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    private fun applyIfNeeded() {
        if (chapters.isEmpty()) return
        val index = chapters.indexOfLast { it.startMs <= player.currentPosition.coerceAtLeast(0L) }
            .coerceAtLeast(0)
        if (index == appliedChapterIndex) return
        appliedChapterIndex = index
        val item = player.currentMediaItem ?: return
        val updated = item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    // See the comment in PlaybackService.playableItem() — ARTIST, not
                    // `subtitle`, is what Android Auto's now-playing template actually shows.
                    .setArtist(chapterLabel(index, chapters[index].title))
                    .build(),
            )
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }

    private companion object {
        /** Coarse enough for chapters (typically minutes long) without spamming the session. */
        const val TICK_MS = 3_000L
    }
}
