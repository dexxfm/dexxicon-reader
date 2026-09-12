package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.dexxicon.reader.core.designsystem.generated.resources.Res
import net.dexxicon.reader.core.designsystem.generated.resources.archivo_extrabold
import net.dexxicon.reader.core.designsystem.generated.resources.archivo_regular
import net.dexxicon.reader.core.designsystem.generated.resources.archivo_semibold
import org.jetbrains.compose.resources.Font

// Phase 4 (issue #115) — Archivo, the mockup's own display/body face (`--font-heading` /
// `--font-body` in the "Modernist" base design system it's built on), bundled as static
// weights rather than Android's Downloadable Fonts API: DLF needs Google Play Services and a
// certificate-fingerprint provider config that doesn't otherwise exist in this project, and
// iOS has no access to it at all — a bundled file works identically on both. Files + OFL
// license text: docs/licenses/ARCHIVO_OFL.txt.
//
// Phase 4 restructure (issue #126) — this used to be two copies (this module's own
// R.font.*-based version, and an identical one in shared/theme/Type.kt using Compose
// Multiplatform resources). Now that this module is KMP, there's one copy, loaded via Compose
// Multiplatform resources (`org.jetbrains.compose.resources.Font`) everywhere — that loader is
// `@Composable`, unlike Android's plain resource-id `Font(R.font.*)`, so [dexxiconTypography]
// is a function rather than a top-level `val`; [DexxiconTheme] calls it from within its own
// composition.
@Composable
private fun archivo(): FontFamily = FontFamily(
    Font(Res.font.archivo_regular, FontWeight.Normal),
    Font(Res.font.archivo_semibold, FontWeight.SemiBold),
    Font(Res.font.archivo_extrabold, FontWeight.ExtraBold),
)

/** Applies [family] to every M3 type-scale slot, keeping each slot's own weight/size/spacing —
 * the mockup uses one face for both heading and body, so this is a blanket swap rather than
 * per-slot cherry-picking. Weights this family doesn't declare (e.g. M3's default Medium on
 * several slots) fall back to Compose's normal nearest-match font synthesis. */
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
