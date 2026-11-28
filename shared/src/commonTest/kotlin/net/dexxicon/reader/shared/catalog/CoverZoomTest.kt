package net.dexxicon.reader.shared.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverZoomTest {
    private val viewport = Size(1000f, 2000f)
    private val center = Offset(500f, 1000f)

    @Test
    fun zoomIsClampedBetweenOneAndMax() {
        val out = CoverTransform().transformedBy(center, Offset.Zero, 0.2f, viewport)
        assertEquals(1f, out.scale)
        val max = CoverTransform().transformedBy(center, Offset.Zero, 100f, viewport)
        assertEquals(MaxCoverZoom, max.scale)
    }

    @Test
    fun cannotPanWhenNotZoomed() {
        val out = CoverTransform().transformedBy(center, Offset(300f, -300f), 1f, viewport)
        assertEquals(0f, out.offset.x + 0f)
        assertEquals(0f, out.offset.y + 0f)
    }

    @Test
    fun panIsClampedToImageEdges() {
        val out = CoverTransform(2f).transformedBy(center, Offset(5000f, -5000f), 1f, viewport)
        assertEquals(Offset(500f, -1000f), out.offset)
    }

    @Test
    fun zoomingOutToOneRecentres() {
        val zoomed = CoverTransform(2f, Offset(400f, 800f))
        val out = zoomed.transformedBy(center, Offset.Zero, 0.5f, viewport)
        assertEquals(CoverTransform(1f, Offset.Zero), out)
    }

    @Test
    fun zoomKeepsCentroidFixed() {
        val centroid = Offset(800f, 1500f)
        val out = CoverTransform().transformedBy(centroid, Offset.Zero, 2f, viewport)
        assertEquals(Offset(-300f, -500f), out.offset)
    }
}
