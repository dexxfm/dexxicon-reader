package net.dexxicon.reader.core.reader

import org.readium.r2.navigator.OverflowableNavigator

/**
 * Wraps a comic's real navigator so that "next/previous page" first steps through the current
 * page's detected panels (smart zoom) before falling through to a real page turn. Delegates
 * every other member of [Navigator]/[VisualNavigator]/[OverflowableNavigator] straight to
 * [delegate], so it's a drop-in for anything typed against [OverflowableNavigator] —
 * [EdgeTapNavigator] and the drag page-turn gesture need no changes to go through it.
 *
 * [panelCount] and [panelIndex] read the current page's panel state; [stepTo] moves within it.
 * A page with no detected panels (empty list) behaves exactly like the undecorated navigator.
 * [onRealPageTurn] fires right before a call falls through to an actual page turn, so the
 * caller knows which end of the new page's panel list to start from (first panel when
 * arriving by [goForward], last panel when arriving by [goBackward]).
 */
class PanelSteppingNavigator(
    private val delegate: OverflowableNavigator,
    private val smartZoomEnabled: () -> Boolean,
    private val panelCount: () -> Int,
    private val panelIndex: () -> Int,
    private val stepTo: (Int) -> Unit,
    private val onRealPageTurn: (forward: Boolean) -> Unit = {},
) : OverflowableNavigator by delegate {

    override fun goForward(animated: Boolean): Boolean {
        if (smartZoomEnabled()) {
            val count = panelCount()
            val index = panelIndex()
            if (count > 0 && index < count - 1) {
                stepTo(index + 1)
                return true
            }
            // Either no confidently-detected panels, or we're leaving the last one on this
            // page — reset so the next page starts at its own first panel.
            stepTo(-1)
        }
        onRealPageTurn(true)
        return delegate.goForward(animated)
    }

    override fun goBackward(animated: Boolean): Boolean {
        if (smartZoomEnabled()) {
            val count = panelCount()
            val index = panelIndex()
            if (count > 0 && index > 0) {
                stepTo(index - 1)
                return true
            }
            stepTo(-1)
        }
        onRealPageTurn(false)
        return delegate.goBackward(animated)
    }
}
