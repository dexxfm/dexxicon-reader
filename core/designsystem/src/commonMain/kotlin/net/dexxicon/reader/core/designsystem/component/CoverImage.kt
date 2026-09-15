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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import net.dexxicon.reader.core.designsystem.theme.CoverShapeSmall
import net.dexxicon.reader.core.model.ContentFormat

/**
 * A book cover with optional overlays: a reading-progress bar along the bottom, a
 * "downloaded" badge (top-right), and a color-coded format badge (bottom-right). Used on
 * the catalog and library grids so both look the same.
 */
@Composable
fun CoverImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /** 0f–1f reading progress; null hides the bar. */
    progress: Float? = null,
    downloaded: Boolean = false,
    /** Content type — shows a color-coded badge bottom-right; null / UNKNOWN hides it. */
    format: ContentFormat? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(COVER_ASPECT)
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
                onState = { state -> failed = state is AsyncImagePainter.State.Error },
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

        if (format != null && format != ContentFormat.UNKNOWN) {
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

const val COVER_ASPECT = 0.66f
