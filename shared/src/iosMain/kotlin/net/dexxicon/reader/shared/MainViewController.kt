package net.dexxicon.reader.shared

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer

/** Entry point the iOS app wraps in a SwiftUI `UIViewControllerRepresentable`. */
fun MainViewController() = ComposeUIViewController {
    val container = remember { createAppContainer(PlatformContext()) }
    App(container)
}
