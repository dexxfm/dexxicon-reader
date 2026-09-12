package net.dexxicon.reader.shared.theme

import androidx.compose.ui.graphics.Color

// Brand palette — dark blue + grey, seeded from the Dexxicon fox logo. Ported verbatim from
// core/designsystem/theme/Color.kt (issue #97) — plain Color constants, no Android dependency.
internal val Navy900 = Color(0xFF0F1B31)
internal val Navy800 = Color(0xFF152238)
internal val Navy700 = Color(0xFF1E3057)
internal val Navy600 = Color(0xFF24406B)
internal val Navy500 = Color(0xFF35507E)
internal val Blue400 = Color(0xFF3D5A8A)
internal val Blue300 = Color(0xFF6E8DC0)
internal val Blue200 = Color(0xFFA9C0E4)

internal val Slate900 = Color(0xFF191D24)
internal val Slate700 = Color(0xFF3A414D)
internal val Slate500 = Color(0xFF6B7688)
internal val Slate300 = Color(0xFF9AA4B4)
internal val Slate200 = Color(0xFFC7CED9)
internal val Slate100 = Color(0xFFE7EBF1)
internal val Slate50 = Color(0xFFF4F6F9)

// Amber (0xFFE8B54B in the native palette) isn't dropped for no reason — it's dead there too
// (no consumer in core/designsystem beyond its own declaration), so it isn't ported here either.
internal val ErrorRed = Color(0xFFB3261E)
internal val ErrorRedDark = Color(0xFFF2B8B5)

// Phase 4 (issue #115) accent — "Aqua", ported verbatim from core/designsystem/theme/Color.kt
// (same issue). See that file's doc comment for what *Hi means and why background/surface
// don't change.
internal val AquaAccentDark = Color(0xFF57C9B4)
internal val AquaAccentDarkHi = Color(0xFF8FDCCD)
internal val AquaAccentLight = Color(0xFF0E7A69)
internal val AquaAccentLightHi = Color(0xFF0A5B4E)
