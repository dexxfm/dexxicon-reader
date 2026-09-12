package net.dexxicon.reader.shared.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Ported verbatim from core/designsystem/theme/Shape.kt (issue #115) — see that file's doc
// comment for why these radii (not M3's defaults, not the mockup's own "Modernist" base
// design system's zero-radius spec) are what's authoritative.
val Pill = RoundedCornerShape(percent = 50)

val CoverShapeSmall = RoundedCornerShape(10.dp)
val CoverShapeMedium = RoundedCornerShape(16.dp)
val CoverShapeLarge = RoundedCornerShape(22.dp)

val DexxiconShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
