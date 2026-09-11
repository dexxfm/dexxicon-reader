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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
    internal var generation = 0

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
 * or image pager never sees them and can't fight the turn. It grabs a still of the current
 * page via [snapshot], paints that on top, and **immediately** moves the navigator, so the
 * page revealed under the lifting still is the real next page from the very start of the drag
 * — not a duplicate of the page you're on. Release past [commitFraction] of the width (or a
 * quick flick) and the turn stands; a shorter drag springs the still back and the move is
 * quietly undone.
 *
 * A second finger (pinch) or a mostly-vertical drag is never claimed, so zoom and scrolling
 * still work. If [snapshot] returns null the turn still happens, just without the lift.
 *
 * @param snapshot a still of the current page, sized to this layout, or null if unavailable.
 * @param onTurn called with `forward = true` for a left swipe; **returns whether the navigator
 *   actually moved** (false at the first / last page). The caller moves the navigator with no
 *   animation of its own — this gesture owns the settle.
 * @param canTurn checked once, right before a drag would otherwise be claimed, with the
 *   direction and the point the gesture started at. Return false to leave every event for
 *   this gesture unconsumed instead — e.g. a pinch-zoomed page that still has room to pan
 *   further in that direction should keep panning, not turn. Defaults to always allowing
 *   the turn (the behaviour before this parameter existed).
 */
fun Modifier.pageTurnGesture(
    state: PageTurnState,
    enabled: Boolean,
    commitFraction: Float,
    snapshot: () -> ImageBitmap?,
    onTurn: (forward: Boolean) -> Boolean,
    canTurn: (forward: Boolean, touchX: Float, touchY: Float) -> Boolean = { _, _, _ -> true },
): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(onTurn)
    val grab by rememberUpdatedState(snapshot)
    val commit by rememberUpdatedState(commitFraction)
    val allowTurn by rememberUpdatedState(canTurn)
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
                // Direction the navigator was actually moved on claim (null at a book edge,
                // where the turn was a no-op — then the still just springs back, nothing to undo).
                var turnedForward: Boolean? = null
                var gen = 0
                var lastX = down.position.x
                // Recency-weighted, not a flat average over the whole drag — a real flick
                // (drag a little, then whip the finger away) needs the *release* speed, and
                // an average from claim to release dilutes that whip with the slower lead-in.
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    if (!claimed && event.changes.count { it.pressed } > 1) break // let pinch through

                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val d = change.positionChange()
                    totalX += d.x
                    totalY += d.y

                    if (!claimed) {
                        if (abs(totalX) > slop && abs(totalX) > abs(totalY) * 1.4f) {
                            val forward = totalX < 0
                            if (!allowTurn(forward, down.position.x, down.position.y)) {
                                break // e.g. a zoomed page with room to pan this way — let it
                            }
                            claimed = true
                            lastX = change.position.x
                            gen = ++state.generation
                            state.outgoing = grab()
                            scope.launch { state.anim.snapTo(0f) }
                            // Move the navigator now, hidden under the still, so the drag
                            // reveals the real next page instead of a copy of this one.
                            if (turn(forward)) turnedForward = forward
                            change.consume()
                        } else if (abs(totalY) > slop) {
                            break // vertical — hand it to the page (scroll / vertical pan)
                        }
                        continue
                    }

                    change.consume()
                    val next = (state.anim.value + (change.position.x - lastX)).coerceIn(-width, width)
                    lastX = change.position.x
                    scope.launch { state.anim.snapTo(next) }
                }

                if (claimed) {
                    val offset = state.anim.value
                    val velocityX = velocityTracker.calculateVelocity().x
                    val flick = abs(velocityX) >= flickVelocityPxPerSec && abs(offset) >= flickDistancePx
                    val tf = turnedForward
                    val stands = when (tf) {
                        true -> offset <= -width * commit || (flick && offset < 0f)
                        false -> offset >= width * commit || (flick && offset > 0f)
                        null -> false
                    }
                    scope.launch { settle(state, gen, tf, stands, width, turn) }
                }
            }
        }
}

/**
 * Finish the gesture: if the turn stands, slide the still off to reveal the page already
 * shown underneath; otherwise spring the still back over the screen and quietly undo the move.
 */
private suspend fun settle(
    state: PageTurnState,
    gen: Int,
    turnedForward: Boolean?,
    stands: Boolean,
    width: Float,
    turn: (Boolean) -> Boolean,
) {
    if (turnedForward != null && stands) {
        state.anim.animateTo(
            targetValue = if (turnedForward) -width else width,
            animationSpec = tween(durationMillis = 200),
        )
    } else {
        // Cover the screen with the still, then undo the move behind it.
        state.anim.animateTo(0f)
        if (turnedForward != null) turn(!turnedForward)
    }
    if (state.generation == gen) {
        state.outgoing = null
        state.anim.snapTo(0f)
    }
}
