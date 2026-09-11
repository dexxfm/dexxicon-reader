package net.dexxicon.reader.core.reader

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Finds comic panel boundaries on a decoded page bitmap by looking for "gutters" — bands of
 * near-uniform background colour wide enough to separate panels — and splitting on them: once
 * across the full page to find rows, then within each row to find its panels. This covers the
 * grid-style layout the large majority of comics use. Pages that don't split at all (dynamic /
 * overlapping panels, splash pages, photographic bleed) come back as an empty list — callers
 * should treat that as "not confident" and show the ordinary full page instead of guessing.
 *
 * Coordinates are fractions of the page (0f..1f in both axes) so they survive display scaling.
 * Panels are returned in reading order: top row to bottom row, and — per [rightToLeft] — left
 * to right or right to left within a row.
 */
object ComicPanelDetector {

    fun detectPanels(bitmap: Bitmap, rightToLeft: Boolean = false): List<RectF> {
        val fullGrid = PixelGrid.from(bitmap) ?: return emptyList()

        // A page snapshot is the whole reader view, not just the page bitmap — when the
        // page's aspect ratio doesn't match the screen (almost always), it's letterboxed
        // against a solid fill colour. That flat border is a *perfectly* uniform band (real
        // page art never is, even its white margins carry a little scan/compression noise),
        // so it's trimmed before background-colour sampling — otherwise the border sample
        // below picks up the letterbox instead of the page, and the whole page reads as
        // "background" with nothing left to split.
        val margins = fullGrid.uniformMargins()
        val grid = fullGrid.subGrid(margins) ?: fullGrid

        val bg = grid.backgroundLuma()

        val rows = grid.bands(0, grid.height) { y -> grid.isBackgroundRow(y, bg) }
            .let { grid.segments(0, grid.height, it) }
        if (rows.size < 2 && !hasInteriorColumnSplit(grid, 0, grid.height, bg)) return emptyList()

        val panels = mutableListOf<IntRect>()
        for ((rowTop, rowBottom) in rows.sortedBy { it.first }) {
            val cols = grid.bands(0, grid.width) { x -> grid.isBackgroundColumn(x, rowTop, rowBottom, bg) }
                .let { grid.segments(0, grid.width, it) }
            val rowPanels = if (cols.isEmpty()) {
                listOf(IntRect(0, rowTop, grid.width, rowBottom))
            } else {
                cols.sortedBy { it.first }.map { (colLeft, colRight) -> IntRect(colLeft, rowTop, colRight, rowBottom) }
            }
                .map { grid.tightenToContent(it, bg) }
                .filter { it.width >= grid.width * MIN_PANEL_FRACTION && it.height >= grid.height * MIN_PANEL_FRACTION }
                .let { if (rightToLeft) it.asReversed() else it }
            panels += rowPanels
        }

        if (panels.size <= 1) return emptyList()

        // Panel fractions are relative to the *original* snapshot (letterbox included) —
        // that's the coordinate space the caller's transform is drawn against — so shift by
        // the trimmed margin before dividing by the untrimmed dimensions.
        return panels.map {
            RectF(
                (margins.left + it.left).toFloat() / fullGrid.width,
                (margins.top + it.top).toFloat() / fullGrid.height,
                (margins.left + it.right).toFloat() / fullGrid.width,
                (margins.top + it.bottom).toFloat() / fullGrid.height,
            )
        }
    }

    /** A page that's a single row can still be a real multi-panel strip — only bail out early
     * (before the cost of a full column scan) when there's clearly nothing to split on. */
    private fun hasInteriorColumnSplit(grid: PixelGrid, top: Int, bottom: Int, bg: Int): Boolean =
        grid.bands(0, grid.width) { x -> grid.isBackgroundColumn(x, top, bottom, bg) }.isNotEmpty()

    private data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** A downsampled greyscale copy of the page, cheap enough to scan pixel-by-pixel. */
    private class PixelGrid private constructor(
        private val luma: ByteArray,
        val width: Int,
        val height: Int,
    ) {
        fun luma(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF

        /**
         * How far a perfectly flat (zero-variance) fill runs in from each edge — a letterbox
         * or pillarbox band, not page content. Real page margins can be flat too (especially
         * a cleanly-rendered digital page with no scan noise), so a run only counts once it's
         * substantial — [MIN_LETTERBOX_FRACTION] of that axis — which ordinary whitespace
         * around the art falls well short of; only a page whose aspect ratio doesn't match the
         * view crosses it. Never trims past the midpoint either, so a page that's genuinely
         * one flat colour throughout still comes back as roughly itself rather than nothing.
         */
        fun uniformMargins(): IntRect {
            var left = 0
            while (left < width / 2 && isUniformColumn(left)) left++
            if (left < width * MIN_LETTERBOX_FRACTION) left = 0

            var right = width
            while (right > width / 2 + 1 && isUniformColumn(right - 1)) right--
            if (width - right < width * MIN_LETTERBOX_FRACTION) right = width

            var top = 0
            while (top < height / 2 && isUniformRow(top)) top++
            if (top < height * MIN_LETTERBOX_FRACTION) top = 0

            var bottom = height
            while (bottom > height / 2 + 1 && isUniformRow(bottom - 1)) bottom--
            if (height - bottom < height * MIN_LETTERBOX_FRACTION) bottom = height

            return IntRect(left, top, right, bottom)
        }

        private fun isUniformRow(y: Int): Boolean {
            val first = luma(0, y)
            for (x in 1 until width) if (luma(x, y) != first) return false
            return true
        }

        private fun isUniformColumn(x: Int): Boolean {
            val first = luma(x, 0)
            for (y in 1 until height) if (luma(x, y) != first) return false
            return true
        }

        /** The sub-region [rect] as its own grid, or null if trimming to it left nothing
         * (a degenerate width/height) — callers should fall back to the untrimmed grid. */
        fun subGrid(rect: IntRect): PixelGrid? {
            if (rect.left == 0 && rect.top == 0 && rect.right == width && rect.bottom == height) return this
            if (rect.width <= 0 || rect.height <= 0) return null
            val sub = ByteArray(rect.width * rect.height)
            for (y in 0 until rect.height) {
                System.arraycopy(luma, (rect.top + y) * width + rect.left, sub, y * rect.width, rect.width)
            }
            return PixelGrid(sub, rect.width, rect.height)
        }

        fun backgroundLuma(): Int {
            // The page margin is background almost by definition — sample a thin border.
            val samples = mutableListOf<Int>()
            val border = maxOf(1, minOf(width, height) / 100)
            for (x in 0 until width step maxOf(1, width / 64)) {
                samples += luma(x, 0)
                samples += luma(x, height - 1)
            }
            for (y in 0 until height step maxOf(1, height / 64)) {
                samples += luma(0.coerceIn(0, width - 1), y)
                samples += luma((width - border).coerceIn(0, width - 1), y)
            }
            return samples.groupingBy { it / BG_BUCKET }.eachCount()
                .maxByOrNull { it.value }
                ?.let { it.key * BG_BUCKET + BG_BUCKET / 2 }
                ?: 255
        }

        fun isBackgroundRow(y: Int, bg: Int): Boolean {
            var hits = 0
            for (x in 0 until width) if (abs(luma(x, y) - bg) <= BG_TOLERANCE) hits++
            return hits >= width * ROW_BG_FRACTION
        }

        fun isBackgroundColumn(x: Int, top: Int, bottom: Int, bg: Int): Boolean {
            var hits = 0
            val span = bottom - top
            for (y in top until bottom) if (abs(luma(x, y) - bg) <= BG_TOLERANCE) hits++
            return hits >= span * ROW_BG_FRACTION
        }

        /** Runs of consecutive indices in [start, end) where [isBg] holds, at least
         * [minGutter] long relative to the scanned span. */
        fun bands(start: Int, end: Int, isBg: (Int) -> Boolean): List<IntRange> {
            val minGutter = maxOf(MIN_GUTTER_PX, ((end - start) * MIN_GUTTER_FRACTION).toInt())
            val bands = mutableListOf<IntRange>()
            var runStart = -1
            for (i in start until end) {
                if (isBg(i)) {
                    if (runStart < 0) runStart = i
                } else if (runStart >= 0) {
                    if (i - runStart >= minGutter) bands += runStart until i
                    runStart = -1
                }
            }
            if (runStart >= 0 && end - runStart >= minGutter) bands += runStart until end
            // A gutter touching the very edge is just the page margin, not a split.
            return bands.filterNot { it.first <= start || it.last >= end - 1 }
        }

        /** The [start, end) span split into the segments between/around [bands], dropping
         * any segment too thin to be a real panel. */
        fun segments(start: Int, end: Int, bands: List<IntRange>): List<Pair<Int, Int>> {
            if (bands.isEmpty()) return emptyList()
            val cuts = bands.sortedBy { it.first }
            val result = mutableListOf<Pair<Int, Int>>()
            var cursor = start
            for (band in cuts) {
                if (band.first > cursor) result += cursor to band.first
                cursor = band.last + 1
            }
            if (cursor < end) result += cursor to end
            val minSize = (end - start) * MIN_PANEL_FRACTION
            return result.filter { (a, b) -> b - a >= minSize }
        }

        /** Shrinks a panel inward while its edge row/column is still background — trims the
         * sliver of gutter a coarse band split leaves attached to a panel's border. */
        fun tightenToContent(rect: IntRect, bg: Int): IntRect {
            var (left, top, right, bottom) = rect
            val maxShrink = ((right - left).coerceAtMost(bottom - top)) / 3
            var shrunk = 0
            while (top < bottom - 1 && shrunk < maxShrink && isBackgroundRow(top, bg)) { top++; shrunk++ }
            shrunk = 0
            while (bottom > top + 1 && shrunk < maxShrink && isBackgroundRow(bottom - 1, bg)) { bottom--; shrunk++ }
            shrunk = 0
            while (left < right - 1 && shrunk < maxShrink && isBackgroundColumn(left, top, bottom, bg)) { left++; shrunk++ }
            shrunk = 0
            while (right > left + 1 && shrunk < maxShrink && isBackgroundColumn(right - 1, top, bottom, bg)) { right--; shrunk++ }
            return IntRect(left, top, right, bottom)
        }

        companion object {
            fun from(bitmap: Bitmap): PixelGrid? {
                if (bitmap.width <= 0 || bitmap.height <= 0) return null
                val scale = MAX_ANALYSIS_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
                val w = (bitmap.width * scale.coerceAtMost(1f)).toInt().coerceAtLeast(1)
                val h = (bitmap.height * scale.coerceAtMost(1f)).toInt().coerceAtLeast(1)
                val scaled = if (w == bitmap.width && h == bitmap.height) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, w, h, true)
                }
                val pixels = IntArray(w * h)
                return runCatching {
                    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                    val luma = ByteArray(w * h)
                    for (i in pixels.indices) {
                        val p = pixels[i]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                    }
                    PixelGrid(luma, w, h)
                }.getOrNull().also { if (scaled !== bitmap) scaled.recycle() }
            }
        }
    }

    private fun abs(v: Int) = if (v < 0) -v else v

    private const val MAX_ANALYSIS_DIMENSION = 800
    private const val BG_TOLERANCE = 18
    private const val BG_BUCKET = 12
    private const val ROW_BG_FRACTION = 0.97
    private const val MIN_GUTTER_FRACTION = 0.008
    private const val MIN_GUTTER_PX = 3
    private const val MIN_PANEL_FRACTION = 0.06
    private const val MIN_LETTERBOX_FRACTION = 0.08
}
