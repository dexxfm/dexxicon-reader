package net.dexxicon.reader.shared

import androidx.compose.ui.window.ComposeUIViewController
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer
import net.dexxicon.reader.shared.reader.AudiobookProgressSync

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
