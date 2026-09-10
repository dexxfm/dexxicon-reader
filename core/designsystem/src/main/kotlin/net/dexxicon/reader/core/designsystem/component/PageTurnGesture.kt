package net.dexxicon.reader.core.designsystem.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * State for [pageTurnGesture]. Opaque to callers — [pageTurnGesture] does the drawing itself.
 */
@Stable
class PageTurnState {
    internal val anim = Animatable(0f)
    internal var outgoing by mutableStateOf<ImageBitmap?>(null)

    /** Horizontal travel of the page being turned away, in pixels. */
    val offsetX: Float get() = anim.value
}

@Composable
fun rememberPageTurnState(): PageTurnState = remember { PageTurnState() }

/**
 * A still of this view and its children, sized to its bounds — the page snapshot
 * [pageTurnGesture] paints while a turn is in flight. Null if the view isn't laid out yet.
 */
fun View.pageSnapshot(): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
}

/**
 * A drag-to-turn-page gesture for a reader whose view can't paginate itself when embedded in
 * an `AndroidView` (the PDF and comic navigators).
 *
 * It watches pointer events on the **Initial** pass and, the moment a drag reads as clearly
 * horizontal and single-finger, claims it — consuming the events so the embedded pdfium view
 * or image pager never sees them and can't fight the turn. It then grabs a still of the current
 * page via [snapshot] and paints that on top; once the drag crosses [commitFraction] of the
 * width (or a quick flick) it tells the navigator to move, so the page you're leaving lifts
 * away — with a soft shadow down its edge — and reveals the real next page already in place,
 * instead of the whole reader sliding off and baring blank space. A shorter drag springs back.
 *
 * A second finger (pinch) or a mostly-vertical drag is never claimed, so zoom and scrolling
 * still work. If [snapshot] returns null the turn still happens, just without the lift.
 *
 * @param snapshot a still of the current page, sized to this layout, or null if unavailable.
 * @param onTurn called with `forward = true` for a left swipe. The caller moves the navigator
 *   **without its own animation** — this gesture owns the settle.
 */
fun Modifier.pageTurnGesture(
    state: PageTurnState,
    enabled: Boolean,
    commitFraction: Float,
    snapshot: () -> ImageBitmap?,
    onTurn: (forward: Boolean) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(onTurn)
    val grab by rememberUpdatedState(snapshot)
    val commit by rememberUpdatedState(commitFraction)
    val density = LocalDensity.current
    val flickVelocityPxPerSec = with(density) { 450.dp.toPx() }
    val shadowPx = with(density) { 20.dp.toPx() }

    this
        .drawWithContent {
            drawContent()
            val page = state.outgoing ?: return@drawWithContent
            val offset = state.offsetX
            // Shadow the lifting page casts onto the page revealed beneath it.
            if (offset < 0f) {
                val edge = size.width + offset
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.30f),
                        1f to Color.Transparent,
                        startX = edge,
                        endX = edge + shadowPx,
                    ),
                    topLeft = Offset(edge, 0f),
                    size = Size((size.width - edge).coerceIn(0f, shadowPx), size.height),
                )
            } else if (offset > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.30f),
                        startX = offset - shadowPx,
                        endX = offset,
                    ),
                    topLeft = Offset((offset - shadowPx).coerceAtLeast(0f), 0f),
                    size = Size(offset.coerceAtMost(shadowPx), size.height),
                )
            }
            drawImage(image = page, topLeft = Offset(offset, 0f))
        }
        .pointerInput(Unit) {
            val width = size.width.toFloat().coerceAtLeast(1f)
            val slop = viewConfiguration.touchSlop
            val flickDistancePx = with(density) { 16.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var totalX = 0f
                var totalY = 0f
                var claimed = false
                // Once the drag crosses the commit line we tell the navigator to move — the
                // page then revealed under the lifting still is the real destination — and from
                // there the turn always completes, so the navigator moves at most once.
                var turnedForward: Boolean? = null
                var startMs = 0L
                var lastX = down.position.x

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    if (!claimed && event.changes.count { it.pressed } > 1) break // let pinch through

                    val d = change.positionChange()
                    totalX += d.x
                    totalY += d.y

                    if (!claimed) {
                        if (abs(totalX) > slop && abs(totalX) > abs(totalY) * 1.4f) {
                            claimed = true
                            startMs = System.currentTimeMillis()
                            lastX = change.position.x
                            scope.launch { state.anim.snapTo(0f) }
                            state.outgoing = grab()
                            change.consume()
                        } else if (abs(totalY) > slop) {
                            break // vertical — hand it to the page (scroll / vertical pan)
                        }
                        continue
                    }

                    change.consume()
                    val next = (state.anim.value + (change.position.x - lastX)).coerceIn(-width, width)
                    lastX = change.position.x
                    if (turnedForward == null && abs(next) >= width * commit) {
                        turnedForward = next < 0
                        turn(next < 0)
                    }
                    scope.launch { state.anim.snapTo(next) }
                }

                if (claimed) {
                    val offset = state.anim.value
                    val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val velocity = abs(offset) / elapsedMs * 1000f
                    if (turnedForward == null &&
                        velocity >= flickVelocityPxPerSec && abs(offset) >= flickDistancePx
                    ) {
                        turnedForward = offset < 0
                        turn(offset < 0)
                    }
                    val committed = turnedForward
                    scope.launch { settle(state, committed, width) }
                }
            }
        }
}

/** Finish the gesture: slide the still off if the turn committed, else spring it home. */
private suspend fun settle(state: PageTurnState, turnedForward: Boolean?, width: Float) {
    if (turnedForward != null) {
        state.anim.animateTo(
            targetValue = if (turnedForward) -width else width,
            animationSpec = tween(durationMillis = 200),
        )
    } else {
        state.anim.animateTo(0f)
    }
    // Drop the still first so a reset of the offset can't flash the old page for a frame.
    state.outgoing = null
    state.anim.snapTo(0f)
}
