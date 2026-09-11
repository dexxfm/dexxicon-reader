package net.dexxicon.reader.core.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicPanelDetectorTest {

    private val panelRects = listOf(
        // left, top, right, bottom — a 2x2 grid of black panels on a white page.
        RectF(20f, 20f, 180f, 280f),
        RectF(220f, 20f, 380f, 280f),
        RectF(20f, 320f, 180f, 580f),
        RectF(220f, 320f, 380f, 580f),
    )

    private fun fourPanelPage(): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        panelRects.forEach { canvas.drawRect(it, paint) }
        return bitmap
    }

    @Test
    fun `finds a 2x2 grid of panels in top-to-bottom, left-to-right order`() {
        val panels = ComicPanelDetector.detectPanels(fourPanelPage())

        assertThat(panels).hasSize(4)
        // Reading order: top-left, top-right, bottom-left, bottom-right.
        assertThat(panels[0].left).isLessThan(panels[1].left)
        assertThat(panels[0].top).isLessThan(panels[2].top)
        assertThat(panels[1].top).isLessThan(panels[3].top)
        assertThat(panels[2].left).isLessThan(panels[3].left)
        // Coordinates come back as page fractions.
        panels.forEach { p ->
            assertThat(p.left).isAtLeast(0f)
            assertThat(p.right).isAtMost(1f)
            assertThat(p.top).isAtLeast(0f)
            assertThat(p.bottom).isAtMost(1f)
        }
    }

    @Test
    fun `right-to-left reverses order within each row, not the row order`() {
        val ltr = ComicPanelDetector.detectPanels(fourPanelPage(), rightToLeft = false)
        val rtl = ComicPanelDetector.detectPanels(fourPanelPage(), rightToLeft = true)

        assertThat(rtl).hasSize(4)
        // Same two rows, top row still first — just column order flipped within each.
        assertThat(rtl[0].left).isGreaterThan(rtl[1].left)
        assertThat(rtl[2].left).isGreaterThan(rtl[3].left)
        assertThat(rtl[0].top).isLessThan(rtl[2].top)
        assertThat(rtl[0]).isEqualTo(ltr[1])
        assertThat(rtl[1]).isEqualTo(ltr[0])
    }

    @Test
    fun `a page with no gutters returns no panels`() {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.GRAY)

        assertThat(ComicPanelDetector.detectPanels(bitmap)).isEmpty()
    }

    @Test
    fun `a single full-bleed panel is not treated as multiple panels`() {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(RectF(10f, 10f, 390f, 590f), Paint().apply { color = Color.BLACK })

        assertThat(ComicPanelDetector.detectPanels(bitmap)).isEmpty()
    }

    /**
     * The bitmap handed to the detector is a snapshot of the whole reader view, not just the
     * page — when the page's aspect ratio doesn't match the screen (true almost always in
     * practice), it's letterboxed against a solid fill. Reproduced here with the same 2x2 page
     * pillarboxed into a wider canvas: pixel-perfect black bars either side, same as an actual
     * reader snapshot.
     */
    @Test
    fun `pillarboxed page still finds its panels, at the right position`() {
        val margin = 200
        val bitmap = Bitmap.createBitmap(400 + margin * 2, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(RectF(margin.toFloat(), 0f, (margin + 400).toFloat(), 600f), Paint().apply { color = Color.WHITE })
        val paint = Paint().apply { color = Color.BLACK }
        panelRects.forEach { canvas.drawRect(RectF(it.left + margin, it.top, it.right + margin, it.bottom), paint) }

        val panels = ComicPanelDetector.detectPanels(bitmap)

        assertThat(panels).hasSize(4)
        // Fractions are relative to the *whole* (letterboxed) snapshot, so the panels should
        // land within the pillarboxed page's own span, not smeared across the black bars.
        val pageLeftFraction = margin / bitmap.width.toFloat()
        val pageRightFraction = (margin + 400) / bitmap.width.toFloat()
        panels.forEach { p ->
            assertThat(p.left).isAtLeast(pageLeftFraction - 0.02f)
            assertThat(p.right).isAtMost(pageRightFraction + 0.02f)
        }
    }
}
