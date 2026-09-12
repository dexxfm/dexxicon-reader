package net.dexxicon.reader.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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

@Composable
fun DexxiconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DexxiconTypography,
        shapes = DexxiconShapes,
        content = content,
    )
}
