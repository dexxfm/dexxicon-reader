package net.dexxicon.reader.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Navy600,
    onPrimary = Slate50,
    primaryContainer = Blue200,
    onPrimaryContainer = Navy900,
    secondary = Slate500,
    onSecondary = Slate50,
    secondaryContainer = Slate200,
    onSecondaryContainer = Slate900,
    tertiary = Blue400,
    onTertiary = Slate50,
    background = Slate50,
    onBackground = Slate900,
    surface = Slate50,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate700,
    outline = Slate500,
    error = ErrorRed,
    onError = Slate50,
)

private val DarkColors = darkColorScheme(
    primary = Blue300,
    onPrimary = Navy900,
    primaryContainer = Navy700,
    onPrimaryContainer = Slate100,
    secondary = Slate300,
    onSecondary = Slate900,
    secondaryContainer = Slate700,
    onSecondaryContainer = Slate100,
    tertiary = Blue200,
    onTertiary = Navy900,
    background = Navy900,
    onBackground = Slate100,
    surface = Navy900,
    onSurface = Slate100,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate300,
    outline = Slate500,
    error = ErrorRedDark,
    onError = Navy900,
)

/**
 * The shared UI's equivalent of `core/designsystem`'s `DexxiconTheme` (issue #97) — `:shared`
 * had been wrapping every screen in a bare `MaterialTheme { }` with no `colorScheme` at all,
 * so it rendered Compose Material3's generic default light scheme instead of the real brand
 * (this navy/slate palette) and had no dark-mode support whatsoever, regardless of the
 * system setting.
 *
 * Deliberately narrower than the native version: no dynamic color (Android 12+
 * wallpaper-seeded theme — genuinely Android-only, `Build.VERSION_CODES.S`/`LocalContext`)
 * and no manual light/dark/system override (the native `AppTheme` setting persists via
 * Android-only DataStore; a multiplatform equivalent is a separate decision this project
 * hasn't made yet). [darkTheme] just follows [isSystemInDarkTheme] — already a multiplatform
 * API (part of `compose.foundation`, already a `:shared` dependency) — matching native's own
 * default behavior before any manual override.
 */
@Composable
fun DexxiconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = DexxiconTypography,
        content = content,
    )
}
