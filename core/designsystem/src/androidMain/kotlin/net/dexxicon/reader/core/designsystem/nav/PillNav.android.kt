package net.dexxicon.reader.core.designsystem.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * issue #320 — with gesture navigation, 2dp: the pill's bottom edge sits just above the 24dp
 * inset, ~12dp above the gesture handle, like YouTube's bottom bar (it used to add 16dp,
 * floating well above). 3-button navigation has tappable buttons at the bottom, and a pill
 * resting flush on that bar looked cramped, so it keeps a 10dp gap there.
 */
@Composable
internal actual fun pillExtraBottomMargin(): Dp {
    val hasNavButtons = WindowInsets.tappableElement.getBottom(LocalDensity.current) > 0
    return if (hasNavButtons) 10.dp else 2.dp
}
