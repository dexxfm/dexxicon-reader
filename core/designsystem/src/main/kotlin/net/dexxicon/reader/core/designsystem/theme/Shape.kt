package net.dexxicon.reader.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Phase 4 (issue #115) — the pill-and-rounded shape language from the Claude Design mockup
 * (`Dexxicon Reader.dc.html`), replacing Material3's default (mostly-square) shapes. The
 * mockup's own "Modernist" base design system is zero-radius by spec (see its readme.md),
 * but the actual markup overrides that pervasively with `border-radius:999px` pills and
 * 10-24px rounded cards — that override, not the base system's readme, is what ships here;
 * per the handoff README ("match the visual output; don't copy the prototype's internal
 * structure"), the rendered design is authoritative over the unused base spec.
 *
 * [Pill] is used directly (not through [DexxiconShapes]) for anything whose rounding must
 * stay a true stadium regardless of its own height — buttons, chips/tags, the search field,
 * the floating nav bar and mini-player, segmented controls. [DexxiconShapes] covers the
 * M3-consumed slots (cards, dialogs, sheets) with the mockup's actual corner radii for those
 * surfaces (covers 10-16px, cards/dialogs 16-24px) rather than M3's own defaults.
 */
val Pill = RoundedCornerShape(percent = 50)

/** Cover art corners specifically — the mockup varies these by cover size (10px small grid
 * tiles up to 22-24px hero covers) more finely than the M3 shape slots below cover; call
 * sites that need a specific cover radius should use [RoundedCornerShape] with [dp] directly
 * rather than stretching one of these named slots to fit. This is the common "list/grid
 * tile" cover radius. */
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
