package net.dexxicon.reader.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * State for [pageTurnGesture]. Feed [offsetX] into the reader view's `graphicsLayer`
 * `translationX` so the page follows the finger.
 */
@Stable
class PageTurnState {
    internal val anim = Animatable(0f)
    val offsetX: Float get() = anim.value
}

@Composable
fun rememberPageTurnState(): PageTurnState = remember { PageTurnState() }

/**
 * A drag-to-turn-page gesture for a reader whose view can't paginate itself when embedded in
 * an `AndroidView` (the PDF and comic navigators). The page follows the finger horizontally;
 * releasing past [commitFraction] of the width — or a quick flick — turns the page via
 * [onTurn], and the incoming page slides the rest of the way in. A shorter drag slides back
 * and stays put. Vertical drags and pinch never start it, so scrolling and zoom still work.
 *
 * @param onTurn called with `forward = true` for a left swipe. The caller should move the
 *   navigator **without its own animation** — this gesture animates the settle.
 */
fun Modifier.pageTurnGesture(
    state: PageTurnState,
    enabled: Boolean,
    commitFraction: Float,
    onTurn: (forward: Boolean) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(onTurn)
    val commit by rememberUpdatedState(commitFraction)
    val flickVelocityPxPerSec = with(LocalDensity.current) { 450.dp.toPx() }

    pointerInput(Unit) {
        val width = size.width.toFloat().coerceAtLeast(1f)
        var startMs = 0L
        detectHorizontalDragGestures(
            onDragStart = { startMs = System.currentTimeMillis() },
            onHorizontalDrag = { _, delta ->
                scope.launch { state.anim.snapTo(state.anim.value + delta) }
            },
            onDragCancel = { scope.launch { state.anim.animateTo(0f) } },
            onDragEnd = {
                val offset = state.anim.value
                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val velocity = abs(offset) / elapsedMs * 1000f
                val committed = abs(offset) >= width * commit || velocity >= flickVelocityPxPerSec
                if (committed && abs(offset) > 1f) {
                    turn(offset < 0)
                    scope.launch {
                        // The navigator has swapped instantly; slide the new page home.
                        state.anim.snapTo(offset)
                        state.anim.animateTo(0f)
                    }
                } else {
                    scope.launch { state.anim.animateTo(0f) }
                }
            },
        )
    }
}
