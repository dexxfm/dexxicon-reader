package net.dexxicon.reader.shared.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

internal const val MaxCoverZoom = 8f

internal data class CoverTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/**
 * Applies one pinch/pan step to the full-screen cover viewer. Scale is clamped to
 * 1..[MaxCoverZoom]; the gesture [centroid] stays fixed while zooming, and the offset is
 * clamped so the image can't be dragged past its edges (and resets to centre at 1×).
 */
internal fun CoverTransform.transformedBy(centroid: Offset, pan: Offset, zoom: Float, viewport: Size): CoverTransform {
    val newScale = (scale * zoom).coerceIn(1f, MaxCoverZoom)
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val focus = centroid - center
    val moved = focus - (focus - offset) * (newScale / scale) + pan
    val maxX = viewport.width * (newScale - 1f) / 2f
    val maxY = viewport.height * (newScale - 1f) / 2f
    return CoverTransform(newScale, Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY)))
}
