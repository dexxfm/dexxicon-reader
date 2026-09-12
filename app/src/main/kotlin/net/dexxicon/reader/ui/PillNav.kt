package net.dexxicon.reader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.navigation.TopLevelDestination

/**
 * Phase 4 (issue #115) — the floating pill bottom nav from the Claude Design mockup, replacing
 * [androidx.compose.material3.NavigationBar]. Icon-only (no labels — the mockup's phone frame
 * never shows one), translucent tonal pill, selected/unselected states are a color swap only
 * (accent vs. muted `onSurfaceVariant`), not a background change — see [PillNavigationRail] for
 * the wider-screen equivalent, which *does* pill-highlight the active icon; the mockup's own
 * `tab()` helper draws exactly this distinction between its compact and rail nav.
 *
 * True floating (overlaying content with a scrim behind it, rather than reserving space below
 * it like [androidx.compose.material3.Scaffold]'s `bottomBar` slot) is deliberately not
 * attempted — this renders inside that slot like the bar it's replacing, just pill-shaped and
 * translucent. Real backdrop blur needs a `RenderEffect` (Android 12+ only, and no direct
 * multiplatform equivalent for the iOS build), so this approximates the mockup's blurred glass
 * look with a semi-transparent tonal surface instead of literally blurring content behind it.
 *
 * Press feedback (both here and in [PillNavigationRail]) is a hand-rolled fade, not
 * `Modifier.indication` + a ripple factory: `androidx.compose.material3.ripple.ripple()`
 * resolves fine in this native module but isn't visible from `:shared`'s commonMain at the
 * Compose Multiplatform version this project pins (material3 1.9.0) — rather than have the
 * two platforms' nav bars diverge in how press feedback is built, both use this same
 * `collectIsPressedAsState` + `animateColorAsState` overlay, which only needs
 * `compose.foundation` (available everywhere).
 */
@Composable
fun FloatingPillNavBar(
    destinations: List<TopLevelDestination>,
    current: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // fillMaxWidth() is what actually makes contentAlignment=Center center the pill — without
    // it this Box shrinks to wrap the pill's own width, and the pill ends up wherever its
    // parent Column (Scaffold's bottomBar slot in DexxiconApp.kt) puts a Start-aligned child:
    // flush left, not centered (issue #115 PR feedback).
    Box(modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = Pill,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                destination.icon,
                                contentDescription = stringResource(destination.labelRes),
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
fun PillNavigationRail(
    destinations: List<TopLevelDestination>,
    current: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
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
                icon = destination.icon,
                label = stringResource(destination.labelRes),
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
