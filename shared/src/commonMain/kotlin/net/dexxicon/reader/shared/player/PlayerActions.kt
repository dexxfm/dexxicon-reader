package net.dexxicon.reader.shared.player

/**
 * The commands the shared player screen (issue #183) can send to whichever native playback
 * engine is actually running — set once by each platform's process-lifetime bridge (Android's
 * `DexxiconApplication.wireMiniPlayer()`; iOS's `ContentView.swift` at startup), the same
 * "point this container's actions at the real native player" step already established for the
 * mini-player's [net.dexxicon.reader.shared.di.AppContainer.onMiniPlayerPlayPause] and friends —
 * bundled into one data class here since there are many more of them than the mini-player ever
 * needed.
 */
data class PlayerActions(
    val playPause: () -> Unit,
    val skipForward: () -> Unit,
    val skipBack: () -> Unit,
    val nextChapter: () -> Unit,
    val previousChapter: () -> Unit,
    val seekTo: (positionMs: Long) -> Unit,
    val seekToChapter: (index: Int) -> Unit,
    val setSpeed: (speed: Float) -> Unit,
    /** Milliseconds until the sleep timer fires, or null to turn it off. */
    val setSleepTimer: (durationMs: Long?) -> Unit,
    val setSleepTimerEndOfChapter: () -> Unit,
)
