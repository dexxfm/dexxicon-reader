package net.dexxicon.reader.core.designsystem.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nadeemiqbal.liquidglass.LiquidGlassDefaults
import io.github.nadeemiqbal.liquidglass.LiquidGlassState
import io.github.nadeemiqbal.liquidglass.liquidGlass
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.model.GlassIntensity

/**
 * Bottom padding a screen's own scrollable content should reserve when the floating nav
 * pill (and, on top of it, the mini-player — see `MiniPlayer`'s own doc comment) can appear
 * over it (issue #132). Sized generously above the pill's own ~68dp visual height (52dp row +
 * 16dp bottom margin) plus a mini-player allowance, rather than exactly matching it — a little
 * extra breathing room above the pill reads better than content stopping flush against it.
 */
val FloatingNavClearance = 96.dp

/**
 * Extra clearance below the pill, on top of the system's own bottom inset
 * (`navigationBarsPadding()`, applied by the caller) — see [FloatingPillNavBar]'s own
 * `padding(bottom = ...)`. Platform-specific because the system inset itself isn't
 * comparable across platforms: Android's gesture-nav inset (~24dp on a stock Pixel-class
 * device) is noticeably smaller than iOS's home-indicator safe area (a fixed 34pt on every
 * Face-ID device), so the same flat extra margin on both left the pill sitting visibly
 * higher on iOS (measured: 34pt inset + 16dp extra = 50pt total vs Android's 24dp + 16dp =
 * 40dp total — confirmed via a real on-device inset readout on both platforms, not
 * estimated from screenshots). iOS's extra margin is reduced so the two totals match.
 */
internal expect val PillExtraBottomMargin: Dp

/**
 * Multiplier applied to a [LiquidGlassDefaults.Tier]'s blur radius / saturation-lift-above-1 /
 * tint alpha — scales the glass effect's strength within whatever quality tier the device is
 * already capped to, rather than overriding the tier (device-safety stays with
 * [io.github.nadeemiqbal.liquidglass.rememberPlatformLiquidGlassQuality], not this setting).
 * [GlassIntensity.OFF] isn't handled here — the caller passes a [LiquidGlassState] already
 * forced to `Fallback` quality for that case, so blur/saturation are no-ops regardless of scale.
 */
private val GlassIntensity.blurScale: Float
    get() = when (this) {
        GlassIntensity.OFF -> 0f
        GlassIntensity.SUBTLE -> 0.5f
        GlassIntensity.STANDARD -> 1f
        GlassIntensity.STRONG -> 1.5f
    }

/**
 * Phase 4 (issue #115) — the floating pill bottom nav from the Claude Design mockup, replacing
 * [androidx.compose.material3.NavigationBar]. Icon-only (no labels — the mockup's phone frame
 * never shows one), translucent tonal pill, selected/unselected states are a color swap only
 * (accent vs. muted `onSurfaceVariant`), not a background change — see [PillNavigationRail] for
 * the wider-screen equivalent, which *does* pill-highlight the active icon.
 *
 * Phase 4 restructure (issue #126) — this used to be two byte-for-byte copies (native
 * `app/ui/PillNav.kt`, `:shared`'s `shared/nav/PillNav.kt`), differing only in how each
 * platform's `TopLevelDestination` enum resolves an icon/label (native's carries a
 * `stringResource` id plus a real navigation-graph route; `:shared`'s carries a plain string
 * and no route at all — genuinely different types, not a styling gap, since a navigation
 * route is a per-platform concern this design-system module has no business depending on).
 * Generic over [T] so both call sites can keep their own destination type and just hand this
 * composable an icon/label resolver instead. [label] is `@Composable` so a caller that has
 * real string resources (native) can use `stringResource` inline; one that doesn't (`:shared`)
 * just returns a plain string.
 *
 * Phase 4 Stage I (issue #132) — both call sites now render this as a `Box` overlay positioned
 * over live content instead of inside a [androidx.compose.material3.Scaffold] `bottomBar` slot
 * (which reserved its own layout space below the content) — true floating, per the mockup's
 * own translucent-pill intent, no longer deferred. [FloatingNavClearance] is the bottom
 * clearance a screen's own scrollable content should reserve so its last item can still scroll
 * fully clear of the pill rather than staying permanently hidden behind it.
 *
 * Issue #224 replaced the flat semi-transparent `Surface` this used to be with real backdrop
 * blur via the `io.github.nadeemiqbal:liquid-glass` library (Compose Multiplatform, with its
 * own per-device quality tiering so this degrades to a zero-allocation tint on low-RAM Android
 * and pre-iOS15 hardware automatically). [glassState] must be created once by the caller (with
 * `rememberLiquidGlassState()`) and shared with whatever `Modifier.liquidGlassSource` marks as
 * this pill's backdrop — see `:shared`'s `App.kt`, the one call site. [glassIntensity] is a
 * user-facing Settings choice (Appearance → Glass intensity) layered on top of the library's
 * own device-tier detection: it scales blur radius / saturation / tint *within* whatever tier
 * the device is capped to, rather than overriding the tier itself, so a low-RAM device still
 * never gets a real blur allocation even if the user picks Strong — [GlassIntensity.OFF] is the
 * one exception, which forces the library's own zero-allocation [io.github.nadeemiqbal
 * .liquidglass.LiquidGlassQuality.Fallback] tier regardless of device capability, since that's
 * the user explicitly asking for no blur at all, not just a lighter one.
 *
 * Press feedback (both here and in [PillNavigationRail]) is a hand-rolled fade, not
 * `Modifier.indication` + a ripple factory: `androidx.compose.material3.ripple.ripple()`
 * resolves fine in a plain Android module but isn't visible from commonMain at the Compose
 * Multiplatform version this project pins (material3 1.9.0) — rather than have the two
 * platforms' nav bars diverge in how press feedback is built, both use this same
 * `collectIsPressedAsState` + `animateColorAsState` overlay, which only needs
 * `compose.foundation` (available everywhere).
 */
@Composable
fun <T> FloatingPillNavBar(
    destinations: List<T>,
    current: T?,
    icon: (T) -> ImageVector,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    glassState: LiquidGlassState,
    glassIntensity: GlassIntensity,
    modifier: Modifier = Modifier,
) {
    // fillMaxWidth() is what actually makes contentAlignment=Center center the pill — without
    // it this Box shrinks to wrap the pill's own width, and the pill ends up wherever its
    // parent Column (the host Scaffold's bottomBar slot) puts a Start-aligned child: flush
    // left, not centered (issue #115 PR feedback).
    Box(modifier.fillMaxWidth().padding(bottom = PillExtraBottomMargin), contentAlignment = Alignment.Center) {
        val tier = LiquidGlassDefaults.forQuality(glassState.quality)
        val scale = glassIntensity.blurScale
        val outline = MaterialTheme.colorScheme.outlineVariant
        // OFF renders the exact pre-#224 look (this project's own themed surfaceVariant tone,
        // fairly opaque) rather than the library's generic light/dark-aware white-or-black
        // tint scaled toward zero — that scaling collapsed the fill to near-invisible (only
        // the shadow + edge sheen showed), and even at full alpha the library's tint is meant
        // to sit *on top of* a blur, not stand alone as this app's "no effect" flat look. Every
        // other level uses the library's own tint, scaled by intensity alongside blur/saturation.
        val tint = if (glassIntensity == GlassIntensity.OFF) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
        } else {
            val baseTint = LiquidGlassDefaults.tintFor(isSystemInDarkTheme())
            baseTint.copy(alpha = (baseTint.alpha * scale).coerceIn(0f, 1f))
        }
        Box(
            Modifier
                .shadow(8.dp, Pill)
                .liquidGlass(
                    state = glassState,
                    shape = Pill,
                    blurRadius = tier.blurRadius * scale,
                    saturation = 1f + (tier.saturation - 1f) * scale,
                    tint = tint,
                    borderHighlight = Brush.verticalGradient(
                        0f to outline.copy(alpha = 0.8f),
                        1f to outline.copy(alpha = 0.1f),
                    ),
                ),
        ) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                destinations.forEach { destination ->
                    val selected = destination == current
                    // The full 74x52dp box is the tap target (accessibility/touch-size), but
                    // the press feedback itself is confined to a 44dp circle behind the icon —
                    // a plain `.selectable()` with no shape-clipped feedback here read as a
                    // hard square flash instead of the mockup's soft, borderless rounded
                    // highlight (see this file's PR discussion, issue #115).
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val overlay by animateColorAsState(
                        if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                        label = "navItemPress",
                    )
                    Box(
                        Modifier
                            .size(width = 74.dp, height = 52.dp)
                            .selectable(
                                selected = selected,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelect(destination) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(overlay),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon(destination),
                                contentDescription = label(destination),
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 4 (issue #115) — the wide-screen (>=600dp) rail from the mockup's turn 4/5 (fold and
 * tablet). Unlike [FloatingPillNavBar], the active destination gets a pill background behind
 * its icon (56x32dp per the mockup markup) and every destination keeps a visible label — dimmed
 * to `onSurfaceVariant` when not selected, full `onSurface` when selected — rather than
 * [current]'s label alone standing out via color as in the compact bar.
 */
@Composable
fun <T> PillNavigationRail(
    destinations: List<T>,
    current: T?,
    icon: (T) -> ImageVector,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // fillMaxHeight() is what actually makes the centered Arrangement below center the icon
    // group within the rail — without it, the Column just wraps its own content height and
    // sits at the top of whatever the parent Row gives it (issue #115 PR feedback: same root
    // cause as the compact bar's earlier missing fillMaxWidth()).
    Column(
        modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant))
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        destinations.forEach { destination ->
            RailItem(
                icon = icon(destination),
                label = label(destination),
                selected = destination == current,
                onClick = { onSelect(destination) },
            )
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    // Same split as FloatingPillNavBar: the whole column (icon + label) is the click target,
    // but the press feedback is confined to the icon's own pill (a real Shape here, not just a
    // circle — the pill is already visible at rest when selected, so its feedback should
    // follow that same shape). The selected tint and the press overlay are two stacked
    // `.background()` layers rather than one combined color, so a press on an already-selected
    // item still visibly darkens instead of being a no-op.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressOverlay by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        label = "railItemPress",
    )
    Column(
        Modifier
            .width(72.dp)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val iconColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        val pillColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent
        val pillShape: Shape = Pill
        Box(
            Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(pillShape)
                .background(pillColor)
                .background(pressOverlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
