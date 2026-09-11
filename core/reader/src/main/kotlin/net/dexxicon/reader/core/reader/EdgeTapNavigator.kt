package net.dexxicon.reader.core.reader

import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import kotlin.math.abs

/**
 * Page-turn gestures for a Readium navigator:
 *
 *  - **Tap** near the left/right edge → previous/next page; tap in the middle → [onCenterTap]
 *    (toggles the reader chrome). When [enabled] is false every tap is a centre tap so the
 *    reader's own controls still work.
 *  - **Horizontal swipe** → previous/next page. Readium consumes horizontal drags itself in
 *    paginated mode, so this only takes effect where it otherwise wouldn't (e.g. vertical
 *    scroll mode), giving a consistent left/right page turn in every layout.
 *
 * [OverflowableNavigator.goForward]/[goBackward][OverflowableNavigator.goBackward] decide +1
 * vs -1 from the *system locale's* layout direction, not the book's own reading direction —
 * so for a manga read right-to-left on an otherwise LTR device, calling the "correct" method
 * for the edge tapped would still turn pages the wrong way. [rightToLeft] flips which edge
 * (and swipe direction) maps to which call, independent of the system locale.
 */
class EdgeTapNavigator(
    private val navigator: OverflowableNavigator,
    private val viewWidth: () -> Int,
    private val onCenterTap: () -> Unit,
    private val enabled: () -> Boolean,
    private val rightToLeft: () -> Boolean = { false },
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
        val width = viewWidth().takeIf { it > 0 }
        if (!enabled() || width == null) {
            onCenterTap()
            return true
        }
        val x = event.point.x
        val rtl = rightToLeft()
        return when {
            x < width * EDGE_FRACTION -> if (rtl) navigator.goForward(true) else navigator.goBackward(true)
            x > width * (1f - EDGE_FRACTION) -> if (rtl) navigator.goBackward(true) else navigator.goForward(true)
            else -> {
                onCenterTap()
                true
            }
        }
    }

    override fun onDrag(event: DragEvent): Boolean {
        if (event.type != DragEvent.Type.End) return false
        // In paginated mode the navigator turns pages on a swipe itself; only step in for
        // vertical-scroll layouts, where a horizontal swipe would otherwise do nothing.
        if (!navigator.overflow.value.scroll) return false
        val width = viewWidth().takeIf { it > 0 } ?: return false
        val dx = event.offset.x
        val dy = event.offset.y
        if (abs(dx) < width * SWIPE_FRACTION || abs(dx) < abs(dy) * 1.5f) return false
        val forward = dx < 0
        val rtl = rightToLeft()
        return if (forward != rtl) navigator.goForward(true) else navigator.goBackward(true)
    }

    private companion object {
        const val EDGE_FRACTION = 0.28f
        const val SWIPE_FRACTION = 0.18f
    }
}
