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
import androidx.compose.ui.graphics.drawscope.translate
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
 * @param atBoundary checked alongside [canTurn] at the same claim point: true means this
 *   direction is already known to be a no-op (the first page going backward, the last page
 *   going forward). The drag is still claimed — so it doesn't leak into the page view as a
 *   scroll/pan — but renders as a rubber-band pull on the live page itself (no snapshot, no
 *   navigator call at all) rather than the normal lift-and-reveal-the-next-page turn, and
 *   always springs back regardless of distance or flick speed. Defaults to never at a
 *   boundary (the behaviour before this parameter existed).
 */
fun Modifier.pageTurnGesture(
    state: PageTurnState,
    enabled: Boolean,
    commitFraction: Float,
    snapshot: () -> ImageBitmap?,
    onTurn: (forward: Boolean) -> Boolean,
    canTurn: (forward: Boolean, touchX: Float, touchY: Float) -> Boolean = { _, _, _ -> true },
    atBoundary: (forward: Boolean) -> Boolean = { false },
): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(onTurn)
    val grab by rememberUpdatedState(snapshot)
    val commit by rememberUpdatedState(commitFraction)
    val allowTurn by rememberUpdatedState(canTurn)
    val boundary by rememberUpdatedState(atBoundary)
    val density = LocalDensity.current
    val flickVelocityPxPerSec = with(density) { 450.dp.toPx() }
    val shadowPx = with(density) { 20.dp.toPx() }
    // How far the rubber-band pull can stretch, in the classic UIScrollView sense: the
    // offset asymptotically approaches this as the raw drag distance grows, never reaching
    // it outright — a light drag stretches almost linearly, a hard one visibly resists.
    val elasticMaxPx = with(density) { 72.dp.toPx() }

    this
        .drawWithContent {
            val page = state.outgoing
            if (page == null) {
                // Idle (offset 0) or an elastic boundary pull in progress (offset != 0) —
                // either way, no snapshot to lift: just translate the live page itself.
                translate(left = state.offsetX) { this@drawWithContent.drawContent() }
                return@drawWithContent
            }
            drawContent()
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

                // The still's travel, tracked synchronously — state.anim.value can't be read
                // for this: snapTo() only takes effect once its launched coroutine actually
                // runs, which (for an ordinary multi-event drag) happens naturally between
                // iterations of this loop, at the next suspending await. A flick that claims
                // and releases within a single event never reaches another suspension point
                // before the block below reads it, so it would always see a stale 0 — this
                // local mirror is what claim-seeding and the commit/flick check below use
                // instead, kept exactly in step with every snapTo() call.
                var offsetPx = 0f
                // Set once, at claim, when this drag's direction is already known to be a
                // no-op (the book's first/last page). Rendered as a damped rubber-band pull
                // on the live page rather than a real turn — see [atBoundary].
                var elastic = false
                // Raw (undamped) cumulative delta since claim — rubberBand() is reapplied to
                // this each time, rather than compounding damping onto an already-damped
                // offsetPx, which would stretch far more slowly than intended.
                var elasticRaw = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // A genuinely rapid flick can arrive as just down-then-up with barely any —
                    // or zero — move events in between; most (or all) of the travel and speed
                    // show up only in this final, "already released" sample. Read it before
                    // checking `pressed`, or a real fast flick can silently fail to claim at all
                    // (and even once it does claim, register with ~zero travel to show for it).
                    val stillDown = change.pressed
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
                            elastic = boundary(forward)
                            if (elastic) {
                                // Already known to be a no-op — no snapshot, no navigator
                                // call, just a damped pull on the live page.
                                elasticRaw = totalX
                                offsetPx = rubberBand(elasticRaw, elasticMaxPx)
                                scope.launch { state.anim.snapTo(offsetPx) }
                            } else {
                                state.outgoing = grab()
                                // Seeded from the actual distance covered so far (usually just
                                // past slop for an ordinary gradual drag) rather than always 0
                                // — so a flick that claims and releases within this same
                                // sample still has real travel to show for it, instead of
                                // resetting to a flush 0 that no subsequent event is left to
                                // move away from.
                                offsetPx = totalX.coerceIn(-width, width)
                                scope.launch { state.anim.snapTo(offsetPx) }
                                // Move the navigator now, hidden under the still, so the drag
                                // reveals the real next page instead of a copy of this one.
                                if (turn(forward)) turnedForward = forward
                            }
                            change.consume()
                        } else if (abs(totalY) > slop) {
                            break // vertical — hand it to the page (scroll / vertical pan)
                        }
                        if (!stillDown) break
                        continue
                    }

                    if (stillDown) change.consume()
                    if (elastic) {
                        elasticRaw += change.position.x - lastX
                        offsetPx = rubberBand(elasticRaw, elasticMaxPx)
                    } else {
                        offsetPx = (offsetPx + (change.position.x - lastX)).coerceIn(-width, width)
                    }
                    lastX = change.position.x
                    scope.launch { state.anim.snapTo(offsetPx) }
                    if (!stillDown) break
                }

                if (claimed) {
                    val offset = offsetPx
                    val velocityX = velocityTracker.calculateVelocity().x
                    val flick = abs(velocityX) >= flickVelocityPxPerSec && abs(offset) >= flickDistancePx
                    val tf = turnedForward
                    val stands = when (tf) {
                        true -> offset <= -width * commit || (flick && offset < 0f)
                        false -> offset >= width * commit || (flick && offset > 0f)
                        null -> false
                    }
                    scope.launch { settle(state, gen, tf, stands, offset, width, turn) }
                }
            }
        }
}

/**
 * The classic UIScrollView rubber-band curve: [rawDelta] asymptotically maps onto
 * (-[max], [max]) — a light pull tracks the finger almost linearly, a hard one visibly
 * resists, and it can never actually reach [max] no matter how far the raw drag goes.
 */
private fun rubberBand(rawDelta: Float, max: Float, resistance: Float = 0.55f): Float {
    val x = abs(rawDelta)
    val damped = (x * max * resistance) / (max + resistance * x)
    return if (rawDelta < 0f) -damped else damped
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
    offset: Float,
    width: Float,
    turn: (Boolean) -> Boolean,
) {
    // The gesture's own snapTo() calls race this coroutine's dispatch (the last one may not
    // have run yet, especially for a flick claimed and released within a single event) — snap
    // to the authoritative offset here rather than trust whatever state.anim already holds.
    state.anim.snapTo(offset)
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
