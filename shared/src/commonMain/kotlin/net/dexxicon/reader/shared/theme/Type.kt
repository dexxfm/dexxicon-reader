package net.dexxicon.reader.shared.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.dexxicon.reader.shared.generated.resources.Res
import net.dexxicon.reader.shared.generated.resources.archivo_extrabold
import net.dexxicon.reader.shared.generated.resources.archivo_regular
import net.dexxicon.reader.shared.generated.resources.archivo_semibold
import org.jetbrains.compose.resources.Font

// Phase 4 (issue #115) — Archivo, ported from core/designsystem/theme/Type.kt (same issue);
// see that file's doc comment for why it's bundled rather than loaded via a downloadable-font
// API. The Compose Multiplatform resource loader (`org.jetbrains.compose.resources.Font`) is
// `@Composable`, unlike Android's plain resource-id `Font(R.font.*)` — that's the one real
// difference from the native version, and why this file's `DexxiconTypography` had to become
// a function instead of a top-level `val`; [Theme.kt] calls it from within `DexxiconTheme`'s
// own composition.
@Composable
private fun archivo(): FontFamily = FontFamily(
    Font(Res.font.archivo_regular, FontWeight.Normal),
    Font(Res.font.archivo_semibold, FontWeight.SemiBold),
    Font(Res.font.archivo_extrabold, FontWeight.ExtraBold),
)

private fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

@Composable
internal fun dexxiconTypography(): Typography = Typography().withFontFamily(archivo()).run {
    copy(
        headlineMedium = headlineMedium.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
        titleLarge = titleLarge.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
        labelLarge = labelLarge.merge(TextStyle(letterSpacing = 0.1.sp)),
    )
}
