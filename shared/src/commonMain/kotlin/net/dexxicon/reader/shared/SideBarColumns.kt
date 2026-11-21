package net.dexxicon.reader.shared

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Issue #343 — whether a side safe-area inset on one edge only can mean a column of system
 * bars (the iPhone Duo's outer screen, and its inner screen in landscape), where Apple's
 * guidelines put tab bars and toolbars. Only iOS: on Android a one-sided inset is a cutout or
 * the 3-button navigation bar.
 */
internal expect val platformHasSideBarColumns: Boolean

/**
 * Issue #343 — a system symbol (an SF Symbol on iOS) for controls that sit among the system's
 * own, like the side-column nav beside the status icons. Null where there are none (Android),
 * and the caller falls back to its Material icon.
 */
@Composable
internal expect fun systemSymbol(name: String): Painter?

/**
 * Issue #343 — a column of system bars down one side of the screen: the iPhone Duo's outer
 * screen, and its inner screen in landscape, where the camera and status bar sit at the top of
 * the trailing edge and iOS reports the column as a side safe-area inset on that edge only.
 * Apple's guidelines put tab bars and toolbars in that column as symbols, not text, so the app's
 * nav and each screen's toolbar stand there as round buttons. Content stays beside the column.
 */
internal class SideBarColumn(val alignment: Alignment, val width: Dp, val onRight: Boolean) : SideControls

/**
 * Where a screen's own controls go on the iPhone Duo: the side column ([SideBarColumn]), or, with
 * the inner screen held upright, the status band across the top ([StatusBand]).
 */
internal sealed interface SideControls

/**
 * Issue #355 — the iPhone Duo's inner screen held upright: the status area is a tall band across
 * the top ([height]), Apple's status icons on its right, so a screen's controls (and the app's
 * nav) stand in a row centred across it, level with those icons, instead of a bar below it.
 */
internal class StatusBand(val height: Dp) : SideControls

/**
 * Issue #343 — the gap kept on the edge away from the side column, so a screen's text (the Home
 * title, a card's percentage) clears the display's rounded corners now that it runs full height.
 */
internal val SIDE_COLUMN_EDGE_GAP = 5.dp

/** A side inset below this counts as none (rounding, or a sliver of the display's corner). */
private val NO_SIDE_INSET = 8.dp

/** Narrowest side column a round button fits in with some room either side. */
private val MIN_SIDE_COLUMN = 68.dp

/**
 * Issue #355 — a top inset at least this tall is the Duo's status band (about 82 pt). Other
 * iPhones' top insets (the Dynamic Island's included) stay near 62 pt, so they never reach it.
 */
private val MIN_STATUS_BAND = 72.dp

/** Where the status icons sit in the band: a little below its middle, so the row is too. */
internal val STATUS_BAND_ROW_OFFSET = 14.dp

/**
 * The side column on this screen, or null: other iPhones have no side inset in portrait and one
 * on both sides in landscape, and Android never uses this.
 */
@Composable
internal fun sideBarColumn(): SideBarColumn? {
    if (!platformHasSideBarColumns) return null
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val left = with(density) { insets.getLeft(density, direction).toDp() }
    val right = with(density) { insets.getRight(density, direction).toDp() }
    return when {
        right >= MIN_SIDE_COLUMN && left < NO_SIDE_INSET -> SideBarColumn(AbsoluteAlignment.CenterRight, right, onRight = true)
        left >= MIN_SIDE_COLUMN && right < NO_SIDE_INSET -> SideBarColumn(AbsoluteAlignment.CenterLeft, left, onRight = false)
        else -> null
    }
}

/** Issue #355 — the Duo's status band across the top, or null (no band, or a side column). */
@Composable
internal fun statusBand(): StatusBand? {
    if (!platformHasSideBarColumns) return null
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val top = with(density) { insets.getTop(density).toDp() }
    val left = with(density) { insets.getLeft(density, direction).toDp() }
    val right = with(density) { insets.getRight(density, direction).toDp() }
    return if (top >= MIN_STATUS_BAND && left < NO_SIDE_INSET && right < NO_SIDE_INSET) StatusBand(top) else null
}

/** Where this screen's own controls go on the Duo: the side column, else the status band, else null. */
@Composable
internal fun sideControls(): SideControls? = sideBarColumn() ?: statusBand()

/**
 * Issue #343 — on the iPhone Duo the status bar and the controls live in the side column, so the
 * safe area above and below the content isn't needed for anything: marks it used up, so every
 * Scaffold, top bar and bar padding inside lets the content run the full height. Elsewhere (no
 * side column) this does nothing.
 */
@Composable
internal fun Modifier.sideColumnFullHeight(): Modifier =
    if (sideBarColumn() != null) consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)) else this

/**
 * A screen's controls in the side column, centred down it like the nav: Apple's order puts
 * navigation (Back) first, then the actions, with a [SideColumnGap] between groups. In the status
 * band (issue #355) the same buttons run in a row centred across the top, level with the status icons.
 */
@Composable
internal fun BoxScope.SideColumnControls(placement: SideControls, content: @Composable () -> Unit) {
    when (placement) {
        is SideBarColumn -> Column(
            Modifier.align(placement.alignment).width(placement.width),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
        is StatusBand -> Row(
            Modifier
                .align(Alignment.TopCenter)
                .height(placement.height)
                .padding(top = STATUS_BAND_ROW_OFFSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

/** The space between two groups of side-column buttons (across, in the status band's row). */
@Composable
internal fun SideColumnGap() {
    Spacer(Modifier.size(10.dp))
}

/**
 * One round side-column button, the same as the side-column nav's: a soft fill, no shadow, and
 * the system's own [symbol] where there is one ([fallback] elsewhere). [active] marks a control
 * that's on (a bookmarked page), the way the nav marks the current destination.
 */
@Composable
internal fun SideColumnButton(
    symbol: String,
    fallback: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val fill = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val painter = systemSymbol(symbol)
        if (painter != null) {
            Icon(painter, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        } else {
            Icon(fallback, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}
