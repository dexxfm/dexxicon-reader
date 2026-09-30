package net.dexxicon.reader.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * issue #306 — status/navigation bar icons (and the 3-button bar's contrast scrim, which
 * follows the same flag) match the theme actually drawn, i.e. the in-app Light/Dark setting
 * rather than the system's. Replaces androidx `enableEdgeToEdge()`, which Play flags for its
 * deprecated `Window.setStatusBarColor`/`setNavigationBarColor` calls; on Android 15+ the app
 * (targetSdk 35+) is edge-to-edge without it.
 */
@Composable
internal actual fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
