package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System font stack for now; a bundled display face can be swapped in later.
internal val DexxiconTypography: Typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
        titleLarge = titleLarge.merge(TextStyle(fontWeight = FontWeight.SemiBold)),
        labelLarge = labelLarge.merge(TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.1.sp)),
    )
}
