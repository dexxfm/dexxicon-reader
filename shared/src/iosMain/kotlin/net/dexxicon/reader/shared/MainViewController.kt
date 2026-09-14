package net.dexxicon.reader.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.HighlightColor
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer
import net.dexxicon.reader.shared.player.NowPlaying
import net.dexxicon.reader.shared.player.PlayerActions
import net.dexxicon.reader.shared.player.PlayerScreen
import net.dexxicon.reader.shared.player.PlayerUiSnapshot
import net.dexxicon.reader.shared.reader.AudiobookProgressSync
import net.dexxicon.reader.shared.reader.epub.EpubProgressBridge
import net.dexxicon.reader.shared.reader.epub.EpubReaderActions
import net.dexxicon.reader.shared.reader.epub.EpubReaderNativeState
import net.dexxicon.reader.shared.reader.epub.EpubReaderScreen
import net.dexxicon.reader.shared.reader.epub.EpubReaderUiState
import net.dexxicon.reader.shared.reader.epub.TocEntry
import net.dexxicon.reader.shared.reader.epub.progressionFromLocatorJson
import net.dexxicon.reader.shared.reader.epub.titleFromLocatorJson
import platform.UIKit.UIViewController
import kotlin.math.abs

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

/**
 * Phase 2 of the shared-reader-chrome redesign (issue #183) — lets Swift's
 * `EpubReaderViewController` resolve/report reading position and (elsewhere) push preferences,
 * the same "hand a piece of the container to Swift" shape as [audiobookProgressSync].
 */
fun epubProgressBridge(): EpubProgressBridge = appContainer.epubProgressBridge

/** Swift's `EPUBNavigatorDelegate` calls this on every `locationDidChange`/on open, the same
 * "native engine pushes a fresh snapshot" shape as [updatePlayerState]. */
fun updateEpubReaderState(state: EpubReaderNativeState?) {
    appContainer.updateEpubReaderState(state)
}

/**
 * Wires the shared EPUB chrome's navigation/preference commands back to the real Readium
 * Swift navigator — called once by Swift right after it builds the navigator (it needs the
 * real `Publication`/`Locator` types the shared chrome never sees), same convention as
 * [setPlayerActions].
 */
fun setEpubReaderActions(
    goToBookmark: (Bookmark) -> Unit,
    goToHighlight: (Highlight) -> Unit,
    goToToc: (TocEntry) -> Unit,
    jumpToRemoteResume: () -> Unit,
    submitPreferences: (ReaderDisplayPreferences) -> Unit,
    applyHighlights: (List<Highlight>) -> Unit,
) {
    appContainer.epubReaderActions = EpubReaderActions(
        goToBookmark = goToBookmark,
        goToHighlight = goToHighlight,
        goToToc = goToToc,
        jumpToRemoteResume = jumpToRemoteResume,
        submitPreferences = submitPreferences,
        applyHighlights = applyHighlights,
    )
}

/** A native edge-tap-navigator centre tap toggles the shared chrome's visibility — called by
 * Swift's `EPUBNavigatorDelegate.navigator(_:didTapAt:)` when a tap lands outside the page-turn
 * edge zones, the Swift equivalent of Android's own `EdgeTapNavigator(onCenterTap = ...)`. */
fun epubReaderToggleChrome() {
    appContainer.toggleEpubChrome()
}

/** A native decoration tap opens the highlight editor sheet — called by Swift's
 * `observeDecorationInteractions(inGroup:onActivated:)` callback. `null` closes it (matches
 * the shared chrome's own `onActiveHighlightChange(null)` dismiss path). */
fun epubReaderSetActiveHighlight(id: String?) {
    appContainer.setEpubActiveHighlightId(id)
}

/** The text-selection "Highlight" menu item's action handler calls this directly — entirely
 * native-triggered (never routed through the shared chrome), so it's a plain fire-and-forget
 * function rather than part of [EpubReaderActions]. */
fun epubReaderAddHighlight(
    serverId: String,
    bookId: String,
    locatorJson: String,
    progression: Double,
    text: String,
    chapterTitle: String?,
) {
    appContainer.addEpubHighlight(serverId, bookId, locatorJson, progression, text, chapterTitle)
}

/**
 * Third Compose root for iOS (issue #183) — same shape as [PlayerViewController], but the
 * actual page-rendering surface is genuinely native (Readium's `EPUBNavigatorViewController`,
 * a real `UIViewController` Swift has already built and delegate-wired by the time this is
 * presented), so [navigatorViewController] is embedded via `UIKitViewController` rather than
 * this screen rendering the whole surface itself the way [PlayerViewController] can for audio.
 *
 * [serverId]/[bookId] are only needed here for the two calls that are portable enough to make
 * directly against [AppContainer.bookmarkRepository]/[AppContainer.highlightRepository] —
 * everything that needs the real `Locator`/`Publication` (going *to* a bookmark/highlight/TOC
 * entry, submitting preferences, applying decorations) is answered by
 * [AppContainer.epubReaderActions] instead, which Swift sets right after building the
 * navigator (see [setEpubReaderActions]).
 */
fun EpubReaderViewController(
    onBack: () -> Unit,
    navigatorViewController: UIViewController,
    serverId: String,
    bookId: String,
): UIViewController = ComposeUIViewController {
    val native by appContainer.epubReaderState.collectAsState()
    val chromeVisible by appContainer.epubChromeVisible.collectAsState()
    val activeHighlightId by appContainer.epubActiveHighlightId.collectAsState()
    val bookmarks by appContainer.bookmarkRepository.observe(serverId, bookId).collectAsState(emptyList())
    val highlights by appContainer.highlightRepository.observe(serverId, bookId).collectAsState(emptyList())
    val preferences by appContainer.readerPreferences.preferences.collectAsState(ReaderDisplayPreferences())
    val actions = appContainer.epubReaderActions
    val scope = rememberCoroutineScope()

    // issue #183: pulls server-side bookmarks/highlights into the local DB (and pushes any
    // locally-pending ones) on open — the same two calls Android's EpubReaderViewModel.init{}
    // already makes. Missing this meant iOS could only ever see bookmarks/highlights created
    // on that same device, never ones synced from elsewhere (confirmed live: bookmarks made on
    // Android never showed up here without it).
    LaunchedEffect(serverId, bookId) {
        runCatching { appContainer.bookmarkRepository.syncFromServer(serverId, bookId) }
        runCatching { appContainer.highlightRepository.syncFromServer(serverId, bookId) }
    }

    val screenState = native?.screenState ?: EpubReaderUiState.Loading
    val currentLocatorJson = native?.currentLocatorJson
    val currentProgression = currentLocatorJson?.let(::progressionFromLocatorJson)

    val currentBookmark = remember(bookmarks, currentProgression) {
        val here = currentProgression
        if (here == null) {
            null
        } else {
            bookmarks.filter { !it.isForeign }
                .minByOrNull { abs(it.progression - here) }
                ?.takeIf { abs(it.progression - here) < 0.001 }
        }
    }

    LaunchedEffect(preferences) {
        actions?.submitPreferences?.invoke(preferences)
    }
    LaunchedEffect(highlights) {
        actions?.applyHighlights?.invoke(highlights)
    }

    if (actions != null) {
        EpubReaderScreen(
            state = screenState,
            onBack = onBack,
            preferences = preferences,
            bookmarks = bookmarks,
            highlights = highlights,
            currentBookmark = currentBookmark,
            chromeVisible = chromeVisible,
            activeHighlightId = activeHighlightId,
            onActiveHighlightChange = { appContainer.setEpubActiveHighlightId(it) },
            onAddBookmark = {
                currentLocatorJson?.let { json ->
                    scope.launch {
                        appContainer.bookmarkRepository.add(
                            serverId = serverId,
                            bookId = bookId,
                            locatorJson = json,
                            progression = progressionFromLocatorJson(json) ?: 0.0,
                            title = titleFromLocatorJson(json).orEmpty(),
                        )
                    }
                }
            },
            onDeleteBookmark = { id -> scope.launch { appContainer.bookmarkRepository.delete(id) } },
            onGoToBookmark = actions.goToBookmark,
            onGoToToc = actions.goToToc,
            onGoToHighlight = actions.goToHighlight,
            onSetNote = { id, note -> scope.launch { appContainer.highlightRepository.updateNote(id, note) } },
            onSetColor = { id, color: HighlightColor -> scope.launch { appContainer.highlightRepository.updateColor(id, color) } },
            onDeleteHighlight = { id -> scope.launch { appContainer.highlightRepository.delete(id) } },
            onJumpToRemoteResume = actions.jumpToRemoteResume,
            onDismissRemoteResume = {},
            onUpdatePreferences = { transform -> appContainer.readerPreferences.update(transform) },
            readerContent = {
                UIKitViewController(
                    factory = { navigatorViewController },
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }
}
