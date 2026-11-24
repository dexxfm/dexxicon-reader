package net.dexxicon.reader.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

@Composable
fun rememberReaderFullscreen(): MutableState<Boolean> {
    val view = LocalView.current
    val window = checkNotNull(view.context.findActivity()) { "Reader fullscreen requires an Activity" }.window
    val controller = remember(window) { WindowInsetsControllerCompat(window, window.decorView) }
    val fullscreen = rememberSaveable { mutableStateOf(false) }

    DisposableEffect(controller) {
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            if (Build.VERSION.SDK_INT < 35) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }
    SideEffect {
        if (Build.VERSION.SDK_INT < 35) {
            // Otherwise older Android versions keep reserving navigation-bar space after hide().
            WindowCompat.setDecorFitsSystemWindows(window, !fullscreen.value)
        }
        if (fullscreen.value) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    return fullscreen
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
