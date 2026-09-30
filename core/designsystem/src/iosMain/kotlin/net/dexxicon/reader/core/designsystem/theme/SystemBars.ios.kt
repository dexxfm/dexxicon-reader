package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.runtime.Composable

/** issue #306 — Android-only; iOS's status bar style is left to the SwiftUI host. */
@Composable
internal actual fun SystemBarsAppearance(darkTheme: Boolean) = Unit
