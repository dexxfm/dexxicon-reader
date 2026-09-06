package net.dexxicon.reader.core.reader

import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent

/**
 * Turns a tap near the left/right edge of the page into a previous/next-page gesture, and a
 * tap in the middle into [onCenterTap] (used to toggle the reader chrome). When [enabled]
 * is false, every tap is treated as a centre tap so the reader's own controls still work.
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

    private companion object {
        const val EDGE_FRACTION = 0.28f
    }
}
