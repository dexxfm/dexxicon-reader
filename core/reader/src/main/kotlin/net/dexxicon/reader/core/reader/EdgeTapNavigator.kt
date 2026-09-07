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
 */
class EdgeTapNavigator(
    private val navigator: OverflowableNavigator,
    private val viewWidth: () -> Int,
    private val onCenterTap: () -> Unit,
    private val enabled: () -> Boolean,
) : InputListener {

    override fun onTap(event: TapEvent): Boolean {
        val width = viewWidth().takeIf { it > 0 }
        if (!enabled() || width == null) {
            onCenterTap()
            return true
        }
        val x = event.point.x
        return when {
            x < width * EDGE_FRACTION -> navigator.goBackward(true)
            x > width * (1f - EDGE_FRACTION) -> navigator.goForward(true)
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
        return if (dx < 0) navigator.goForward(true) else navigator.goBackward(true)
    }

    private companion object {
        const val EDGE_FRACTION = 0.28f
        const val SWIPE_FRACTION = 0.18f
    }
}
