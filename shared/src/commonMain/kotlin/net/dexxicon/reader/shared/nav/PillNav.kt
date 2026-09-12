package net.dexxicon.reader.shared.nav

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
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.shared.theme.Pill

// Phase 4 (issue #115) — ported from app/ui/PillNav.kt (native) with the same behavior,
// including the same PR-review fixes: press feedback confined to a shape behind the icon
// (a circle in the compact bar, the same pill the rail's active-state highlight uses)
// instead of the whole rectangular tap target, and the compact bar centered (fillMaxWidth +
// Alignment.Center) rather than left-aligned by its parent Column. Press feedback is a
// hand-rolled fade (collectIsPressedAsState + animateColorAsState), not
// Modifier.indication + a ripple factory — see the native file's doc comment for why
// (androidx.compose.material3.ripple.ripple() isn't visible from this commonMain at the
// Compose Multiplatform version this project pins).

@Composable
fun FloatingPillNavBar(
    destinations: List<TopLevelDestination>,
    current: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                                contentDescription = destination.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PillNavigationRail(
    destinations: List<TopLevelDestination>,
    current: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // fillMaxHeight() is what actually makes the centered Arrangement below center the icon
    // group within the rail — see the native file's doc comment (same fix, same root cause).
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
                label = destination.label,
                selected = destination == current,
                onClick = { onSelect(destination) },
            )
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
