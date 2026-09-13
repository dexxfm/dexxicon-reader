package net.dexxicon.reader.shared

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer
import net.dexxicon.reader.shared.player.NowPlaying
import net.dexxicon.reader.shared.player.PlayerActions
import net.dexxicon.reader.shared.player.PlayerScreen
import net.dexxicon.reader.shared.player.PlayerUiSnapshot
import net.dexxicon.reader.shared.reader.AudiobookProgressSync
import platform.UIKit.UIViewController

/**
 * One process-lifetime [AppContainer] — hoisted out of `MainViewController`'s Compose content
 * (previously a `remember { }` inside it) so a plain top-level Kotlin function can hand a piece
 * of it to Swift too (see [audiobookProgressSync] below), not just the Compose tree. Behavior
 * is unchanged for everything that already went through the old `remember`-scoped instance:
 * this app has exactly one root Compose UI for its whole lifetime on iOS, so a `remember` tied
 * to that composition and a plain process-lifetime singleton are already equivalent in
 * practice — this just makes that equivalence available outside Compose as well.
 */
private val appContainer: AppContainer by lazy { createAppContainer(PlatformContext()) }

/**
 * Entry point the iOS app wraps in a SwiftUI `UIViewControllerRepresentable`.
 *
 * [onOpenReader] (issue #99) is supplied by `ContentView.swift` as a plain Swift closure — the
 * natural, already-supported direction for a Kotlin/Native framework's exported function type,
 * unlike trying to have Kotlin instantiate Swift-authored reader UI directly (Kotlin/Native
 * only interops with Objective-C/C, not arbitrary Swift). Swift's implementation pushes a
 * fully native reader screen on its own `UINavigationController`, outside Compose entirely.
 */
fun MainViewController(onOpenReader: OnOpenReader) = ComposeUIViewController {
    App(appContainer, onOpenReader = onOpenReader)
}

/**
 * issue #114: lets Swift's `AudiobookPlayerViewController` resolve/report playback position
 * without needing its own path into `:shared`'s data layer — the same one process-lifetime
 * [AppContainer] the rest of the app already uses, not a second one. Swift calls this once
 * (`MainViewControllerKt.audiobookProgressSync()`, the same top-level-function convention
 * `MainViewControllerKt.MainViewController(...)` itself already uses) and holds onto the
 * result for as long as it needs it.
 */
fun audiobookProgressSync(): AudiobookProgressSync = appContainer.audiobookProgressSync

/**
 * Phase 4 Stage I (issue #146) — the iOS half of the same bridge Android's
 * `DexxiconApplication.wireMiniPlayer()` builds: `AudiobookPlaybackController`'s own `didSet`
 * observer calls this on every playback state change (see that file's `pushNowPlaying()`), the
 * same top-level-function convention as [audiobookProgressSync]. `null` means nothing is
 * playing, which hides `:shared`'s `MiniPlayer` entirely — see [NowPlaying]'s own doc comment.
 */
fun updateNowPlaying(nowPlaying: NowPlaying?) {
    appContainer.updateNowPlaying(nowPlaying)
}

/**
 * Wires the mini-player's play/pause/dismiss/reopen taps back to the real
 * `AudiobookPlaybackController.shared` — called once from `ContentView.swift`, the same "point
 * this container's actions at the real native player" step Android's `wireMiniPlayer()` does
 * at app startup. See [AppContainer.onMiniPlayerPlayPause]'s own doc comment for why these are
 * plain mutable properties rather than constructor params.
 */
fun setMiniPlayerActions(playPause: () -> Unit, dismiss: () -> Unit, reopen: () -> Unit) {
    appContainer.onMiniPlayerPlayPause = playPause
    appContainer.onMiniPlayerDismiss = dismiss
    appContainer.onMiniPlayerReopen = reopen
}

/**
 * Phase 1 of the shared-reader-chrome redesign (issue #183) — the full player screen's own
 * bridge, alongside (not replacing) [updateNowPlaying]/[setMiniPlayerActions]'s narrower
 * mini-player slice. `AudiobookPlaybackController`'s `didSet` observer calls this on every
 * playback state change, same as it already calls [updateNowPlaying].
 */
fun updatePlayerState(state: PlayerUiSnapshot?) {
    appContainer.updatePlayerState(state)
}

/**
 * Wires the full player screen's commands back to `AudiobookPlaybackController.shared` — called
 * once from `ContentView.swift` at startup, same as [setMiniPlayerActions]. Individual named
 * closure parameters (not a single [PlayerActions] Swift would have to construct) match that
 * function's own established calling convention.
 */
fun setPlayerActions(
    playPause: () -> Unit,
    skipForward: () -> Unit,
    skipBack: () -> Unit,
    nextChapter: () -> Unit,
    previousChapter: () -> Unit,
    seekTo: (Long) -> Unit,
    seekToChapter: (Int) -> Unit,
    setSpeed: (Float) -> Unit,
    setSleepTimer: (Long?) -> Unit,
    setSleepTimerEndOfChapter: () -> Unit,
) {
    appContainer.playerActions = PlayerActions(
        playPause = playPause,
        skipForward = skipForward,
        skipBack = skipBack,
        nextChapter = nextChapter,
        previousChapter = previousChapter,
        seekTo = seekTo,
        seekToChapter = seekToChapter,
        setSpeed = setSpeed,
        setSleepTimer = setSleepTimer,
        setSleepTimerEndOfChapter = setSleepTimerEndOfChapter,
    )
}

/**
 * Second Compose root for iOS (issue #183) — mirrors [MainViewController] itself: a plain
 * top-level function returning a real `UIViewController` via `ComposeUIViewController`, ready
 * for Swift to present modally (via `FullScreenReaderPresentation`) exactly like every other
 * native reader here. Reads the same process-lifetime [appContainer] the rest of the app uses,
 * so it renders whatever `AudiobookPlaybackController` already pushed via [updatePlayerState] —
 * no separate load-on-appear step, since by the time this is presented `ContentView.swift` has
 * already called `AudiobookPlaybackController.shared.start(book:authHeader:)`.
 */
fun PlayerViewController(onBack: () -> Unit): UIViewController = ComposeUIViewController {
    val state by appContainer.playerState.collectAsState()
    val actions = appContainer.playerActions
    if (actions != null) {
        PlayerScreen(state = state, actions = actions, onBack = onBack)
    }
}
