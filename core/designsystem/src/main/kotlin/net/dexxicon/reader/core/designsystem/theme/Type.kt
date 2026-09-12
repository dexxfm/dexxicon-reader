package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.dexxicon.reader.core.designsystem.R

// Phase 4 (issue #115) — Archivo, the mockup's own display/body face (`--font-heading` /
// `--font-body` in the "Modernist" base design system it's built on), bundled as static
// weights rather than Android's Downloadable Fonts API: DLF needs Google Play Services and a
// certificate-fingerprint provider config that doesn't otherwise exist in this project, and
// :shared (KMP, iOS included) has no access to it at all — a bundled file works identically
// on both. Files + OFL license text: docs/licenses/ARCHIVO_OFL.txt. Ported verbatim (down to
// the weight mapping) in shared/theme/Type.kt, which loads the same 3 files via Compose
// Multiplatform resources instead of a resource ID.
internal val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
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

internal val DexxiconTypography: Typography = Typography().withFontFamily(Archivo).run {
    copy(
        headlineMedium = headlineMedium.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
        titleLarge = titleLarge.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
        labelLarge = labelLarge.merge(TextStyle(letterSpacing = 0.1.sp)),
    )
}
