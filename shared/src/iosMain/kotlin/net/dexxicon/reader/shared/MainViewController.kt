package net.dexxicon.reader.shared

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer

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
    val container = remember { createAppContainer(PlatformContext()) }
    App(container, onOpenReader = onOpenReader)
}
