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
        content = content,
    )
}
