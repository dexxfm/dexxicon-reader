package net.dexxicon.reader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A book cover with optional overlays: a reading-progress bar along the bottom and a
 * "downloaded" badge. Used on the catalog and library grids so both look the same.
 */
@Composable
fun CoverImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /** 0f–1f reading progress; null hides the bar. */
    progress: Float? = null,
    downloaded: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(COVER_ASPECT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = contentDescription,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
