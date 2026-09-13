package net.dexxicon.reader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase 5 (issue #115) — wraps a horizontally-scrolling [content] (a `LazyRow` driven by
 * [state]) with a soft scrim fade at whichever edge still has more content to scroll to,
 * hiding it once [state] reports that edge is already at rest ([LazyListState.canScrollBackward]/
 * [LazyListState.canScrollForward] are snapshot-backed, so this recomposes as the row scrolls).
 * [edgeColor] must match whatever's actually drawn behind [content] — the screen's own flat
 * background — for the fade to blend rather than show a visible seam.
 */
@Composable
fun EdgeFadeRow(
    state: LazyListState,
    modifier: Modifier = Modifier,
    edgeColor: Color = MaterialTheme.colorScheme.background,
    fadeWidth: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        if (state.canScrollBackward) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(fadeWidth)
                    .background(Brush.horizontalGradient(listOf(edgeColor, edgeColor.copy(alpha = 0f)))),
            )
        }
        if (state.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(fadeWidth)
                    .background(Brush.horizontalGradient(listOf(edgeColor.copy(alpha = 0f), edgeColor))),
            )
        }
    }
}
