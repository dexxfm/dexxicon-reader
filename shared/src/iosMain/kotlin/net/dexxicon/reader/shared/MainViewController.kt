package net.dexxicon.reader.shared

import androidx.compose.ui.window.ComposeUIViewController

/** Entry point the iOS app wraps in a SwiftUI `UIViewControllerRepresentable`. */
fun MainViewController() = ComposeUIViewController { App() }
