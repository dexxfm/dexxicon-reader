package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Brand palette — dark blue + grey, seeded from the Dexxicon fox logo.
internal val Navy900 = Color(0xFF0F1B31)
internal val Navy800 = Color(0xFF152238)
internal val Navy700 = Color(0xFF1E3057)
internal val Navy600 = Color(0xFF24406B)
internal val Navy500 = Color(0xFF35507E)
// Blue400/Blue300 (the old dark-theme primary/light-theme tertiary before Phase 4's Aqua
// accent) were dropped here once nothing referenced them — Blue200 stays: it's still the
// light-theme primaryContainer tint in Theme.kt.
internal val Blue200 = Color(0xFFA9C0E4)

internal val Slate900 = Color(0xFF191D24)
internal val Slate700 = Color(0xFF3A414D)
internal val Slate500 = Color(0xFF6B7688)
internal val Slate300 = Color(0xFF9AA4B4)
internal val Slate200 = Color(0xFFC7CED9)
internal val Slate100 = Color(0xFFE7EBF1)
internal val Slate50 = Color(0xFFF4F6F9)

internal val Amber = Color(0xFFE8B54B)
internal val ErrorRed = Color(0xFFB3261E)
internal val ErrorRedDark = Color(0xFFF2B8B5)

// Phase 4 (issue #115) accent — "Aqua", one of 4 options in the Claude Design mockup
// (Dexxicon Reader.dc.html), confirmed as the pick to implement. Aqua/AquaHi are the
// mockup's own `d`/`dhi` (dark theme) and `l`/`lhi` (light theme) values for this option —
// *Hi is the emphasis step (pressed state, text-on-tint), not a "highlight" in the
// selection sense. This replaces the old Navy/Blue primary role below; background/surface
// stay on the existing Navy/Slate ground either way (the mockup only swaps the accent
// role between its 4 options, never the ground).
internal val AquaAccentDark = Color(0xFF57C9B4)
internal val AquaAccentDarkHi = Color(0xFF8FDCCD)
internal val AquaAccentLight = Color(0xFF0E7A69)
internal val AquaAccentLightHi = Color(0xFF0A5B4E)
