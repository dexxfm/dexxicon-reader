package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// No iOS equivalent of Android 12+ wallpaper-seeded dynamic color exists — [DexxiconTheme]
// falls back to the fixed Aqua palette regardless of [dynamicColor].
@Composable
internal actual fun resolveDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null
