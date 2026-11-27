package net.dexxicon.reader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import net.dexxicon.reader.core.designsystem.theme.CoverShapeSmall
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.seriesNumberLabel

/**
 * A book cover with optional overlays: a reading-progress bar along the bottom, a
 * "downloaded" badge (top-right), a color-coded format badge (bottom-right), and the book's
 * series number (top-left). Used on the catalog and library grids so both look the same.
 */
@Composable
fun CoverImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /** 0f–1f reading progress; null hides the bar. */
    progress: Float? = null,
    downloaded: Boolean = false,
    /** Content type — shows a color-coded badge bottom-right; null / UNKNOWN hides it, as
     *  does the user turning format badges off ([LocalCoverBadges], issue #252). */
    format: ContentFormat? = null,
    /** Position in its series — shows "#3" top-left when the user has series numbers turned
     *  on ([LocalCoverBadges], issue #257); null hides it. */
    seriesIndex: Double? = null,
    /** The source library's cover shape (width / height) when it states one, e.g. BookOrbit's
     *  per-library square/portrait setting. Null: the shape learned for [coverUrl] from the
     *  loaded image, else portrait. */
    aspectRatio: Float? = null,
    /** Shape of the space reserved for the cover — the tallest cover sharing its row or shelf.
     *  Null: just this cover's own shape. */
    slotAspect: Float? = null,
) {
    if (coverUrl != null && aspectRatio != null) {
        SideEffect { CoverAspects.hint(coverUrl, aspectRatio) }
    }
    val shape = aspectRatio ?: CoverAspects.shapeOf(coverUrl)
    // The cover rests on the bottom edge of its slot, so mixed square and portrait covers in
    // one row or shelf line up along the bottom.
    Box(
        modifier.fillMaxWidth().aspectRatio(slotAspect ?: shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(shape)
            // Phase 4 (issue #115): the mockup's grid-tile cover radius (10px), not M3's
            // default small-shape square-ish corner.
            .clip(CoverShapeSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // issue #163 — a genuine load failure (a 404 for a book with no cover, a
        // scroll-cancelled request that's given up) rendered as the exact same empty grey box
        // as "still loading", so a permanently-stuck cover was indistinguishable from a slow
        // one. Track error state per coverUrl and fall back to the same "no cover" icon used
        // when there's no URL at all, rather than leaving it blank forever.
        var failed by remember(coverUrl) { mutableStateOf(false) }
        if (coverUrl != null && !failed) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    failed = state is AsyncImagePainter.State.Error
                    if (state is AsyncImagePainter.State.Success && aspectRatio == null) {
                        val size = state.painter.intrinsicSize
                        CoverAspects.measured(coverUrl, size.width, size.height)
                    }
                },
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No Cover",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (downloaded) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .size(16.dp),
            )
        }

        val seriesLabel = seriesNumberLabel(seriesIndex)
        if (seriesLabel != null && LocalCoverBadges.current.showSeriesNumber) {
            Text(
                "#$seriesLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }

        if (format != null && format != ContentFormat.UNKNOWN && LocalCoverBadges.current.showFormat) {
            FormatBadge(
                format = format,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 6.dp),
            )
        }

        val p = progress?.coerceIn(0f, 1f)
        if (p != null && p > 0f) {
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // the bar is decorative — the percentage is announced on the card itself
                    .clearAndSetSemantics {},
            )
        }
    }
    }
}

const val COVER_ASPECT = 0.66f
const val SQUARE_COVER_ASPECT = 1f

/** Covers are drawn either square or portrait: near-square images are square, all else portrait. */
fun snapCoverAspect(ratio: Float): Float =
    if (ratio in 0.85f..1.2f) SQUARE_COVER_ASPECT else COVER_ASPECT

/**
 * Cover shapes by cover URL, so a cover keeps its shape in places that only know the URL
 * (Home shelves, downloads). Fed by server-declared shapes and by what loaded images measure.
 */
object CoverAspects {
    private val shapes = mutableStateMapOf<String, Float>()

    fun shapeOf(url: String?): Float = url?.let { shapes[it] } ?: COVER_ASPECT

    /** The slot shape for covers sharing a row or shelf: the tallest one's. */
    fun slotOf(shapes: List<Float>): Float = shapes.minOrNull() ?: COVER_ASPECT

    /** [slotOf] for the grid row holding [index], given [columns] cells per row. */
    inline fun rowSlotOf(size: Int, index: Int, columns: Int, shapeAt: (Int) -> Float): Float {
        val start = index / columns * columns
        var slot = COVER_ASPECT
        var first = true
        for (i in start until minOf(start + columns, size)) {
            val s = shapeAt(i)
            if (first || s < slot) slot = s
            first = false
        }
        return slot
    }

    /** Cells per row of an adaptive grid, matching Compose's `GridCells.Adaptive` maths. */
    fun gridColumns(width: Dp, minCell: Dp = 112.dp, spacing: Dp = 12.dp, padding: Dp = 24.dp): Int =
        ((width - padding + spacing) / (minCell + spacing)).toInt().coerceAtLeast(1)

    fun hint(url: String, ratio: Float) {
        if (shapes[url] != ratio) shapes[url] = ratio
    }

    fun measured(url: String, width: Float, height: Float) {
        if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            hint(url, snapCoverAspect(width / height))
        }
    }
}
