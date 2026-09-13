package net.dexxicon.reader.shared.player

/**
 * Phase 4 Stage I (issue #146) — the deliberately narrow, cross-platform slice of audiobook
 * playback state `:shared`'s [MiniPlayer] needs to render, analogous to
 * [net.dexxicon.reader.shared.reader.AudiobookProgressSync]'s own "narrow slice of a bigger
 * native class" shape. The real player engines stay fully native and out of scope for removal
 * (Android's `core/media/AudiobookPlayer` wraps Media3; iOS's `AudiobookPlaybackController`
 * wraps `AVPlayer`) — neither is portable, and porting the engine itself was never the goal.
 * Each platform's engine pushes a fresh snapshot into
 * [net.dexxicon.reader.shared.di.AppContainer.updateNowPlaying] on every state change (Android:
 * a small bridge collecting `AudiobookPlayer.state`; iOS: `AudiobookPlaybackController`'s own
 * `didSet` observer) — `null` means nothing is playing, which hides the mini-player entirely.
 */
data class NowPlaying(
    val serverId: String,
    val bookId: String,
    val title: String,
    val coverUrl: String?,
    val currentChapterTitle: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)
