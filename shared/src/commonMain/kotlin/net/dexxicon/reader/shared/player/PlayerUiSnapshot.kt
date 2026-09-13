package net.dexxicon.reader.shared.player

/** One chapter's title + start offset — the cross-platform shape of both Android's
 * `core/media/PlayerUiState`'s chapter list and iOS's `AudiobookPlaybackController.ChapterInfo`. */
data class PlayerChapter(val title: String, val startMs: Long)

/**
 * The full cross-platform slice of audiobook-playback state the shared player screen (issue
 * #183) needs — a richer sibling of [NowPlaying], which stays deliberately narrow for the
 * mini-player only (see its own doc comment). The real player engines stay fully native and
 * out of scope for removal (Android's `core/media/AudiobookPlayer` wraps Media3; iOS's
 * `AudiobookPlaybackController` wraps `AVPlayer`) — neither is portable, and porting the engine
 * itself was never the goal. Each platform's engine pushes a fresh snapshot into
 * [net.dexxicon.reader.shared.di.AppContainer.updatePlayerState] on every state change, same
 * bridge shape as [NowPlaying]/`updateNowPlaying`.
 */
data class PlayerUiSnapshot(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    val coverUrl: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    /** Epoch millis the sleep timer will fire at, or null when no timed sleep is armed —
     * mirrors iOS's own `Date?`-shaped `sleepTimerEndsAt`, since a bare "minutes remaining"
     * would keep needing a wall-clock reference to redraw against. */
    val sleepTimerEndsAtEpochMs: Long?,
    val sleepAtChapterEnd: Boolean,
    val currentChapterIndex: Int,
    val chapters: List<PlayerChapter>,
) {
    val currentChapterTitle: String?
        get() = chapters.getOrNull(currentChapterIndex)?.title
}
