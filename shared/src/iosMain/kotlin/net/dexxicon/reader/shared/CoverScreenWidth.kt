package net.dexxicon.reader.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Every iOS Compose host (the app, the player, the readers) is built through this.
 *
 * Issue #343 — the iPhone Duo's cover screen puts its status bar in a column at the top right,
 * around the camera, and iOS reports that column as a right safe-area inset for the screen's
 * whole height. Every `Scaffold` and `TopAppBar` padded for it, leaving an empty strip down
 * the right side. In portrait a phone has no other side inset (the camera housing only
 * becomes one in landscape), so a portrait side inset is consumed here, before any screen
 * sees it: content uses the full width and scrolls past the clock the way it scrolls under
 * a status bar along the top. Landscape keeps its side insets.
 */
internal fun fullWidthComposeViewController(content: @Composable () -> Unit): UIViewController =
    ComposeUIViewController { FullWidthInPortrait(content) }

@Composable
private fun FullWidthInPortrait(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val modifier = if (portrait) {
            Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        } else {
            Modifier.fillMaxSize()
        }
        Box(modifier) { content() }
    }
}
