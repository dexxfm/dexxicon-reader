package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Phase 4 (issue #115): primary/tertiary now carry the Aqua accent (the mockup's confirmed
// pick) instead of the old Navy/Blue brand color — background/surface/secondary are
// untouched, matching the mockup's own behavior of only swapping its `--color-accent*`
// role between its 4 named options. Blue200/300/400 stay declared in Color.kt (still used
// as the light/dark primaryContainer tints below) even though they're no longer the
// primary itself.
private val LightColors = lightColorScheme(
    primary = AquaAccentLight,
    onPrimary = Slate50,
    primaryContainer = Blue200,
    onPrimaryContainer = AquaAccentLightHi,
    secondary = Slate500,
    onSecondary = Slate50,
    secondaryContainer = Slate200,
    onSecondaryContainer = Slate900,
    tertiary = AquaAccentLightHi,
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
    primary = AquaAccentDark,
    onPrimary = Navy900,
    primaryContainer = Navy700,
    onPrimaryContainer = AquaAccentDarkHi,
    secondary = Slate300,
    onSecondary = Slate900,
    secondaryContainer = Slate700,
    onSecondaryContainer = Slate100,
    tertiary = AquaAccentDarkHi,
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
 * Phase 4 restructure (issue #126) — the one `DexxiconTheme`, used by native `:app` and
 * `:shared` alike (previously two near-identical copies: this module's own, with dynamic
 * color, and a narrower one in shared/theme/Theme.kt with no dynamic color and no manual
 * light/dark/system override). [dynamicColor] only ever does anything on Android 12+
 * ([resolveDynamicColorScheme]'s androidMain actual) — its iosMain actual always returns
 * null, so passing `true` on iOS is a harmless no-op rather than a platform check every
 * call site would otherwise need.
 */
@Composable
fun DexxiconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dynamicScheme = if (dynamicColor) resolveDynamicColorScheme(darkTheme) else null
    val colorScheme = dynamicScheme ?: if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = dexxiconTypography(),
        shapes = DexxiconShapes,
        content = content,
    )
}

/** Android 12+ wallpaper-seeded color scheme — genuinely platform-specific (no iOS
 * equivalent), so this is the one thing behind an expect/actual rather than a runtime check
 * every caller would otherwise need. Null means "not available/not requested"; the caller
 * falls back to [LightColors]/[DarkColors]. */
@Composable
internal expect fun resolveDynamicColorScheme(darkTheme: Boolean): ColorScheme?
