package net.dexxicon.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.designsystem.theme.CoverShapeSmall
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.media.PlayerUiState

/**
 * Phase 4 (issue #115). [floating] mirrors the mockup's own split: a floating translucent
 * pill above the compact bottom nav (`<600dp`), or a full-width bar flush with the rail at
 * `>=600dp` (turns 4/5) — same content and controls either way, just the outer [Surface]'s
 * shape, color and margin change.
 */
@Composable
fun MiniPlayer(
    playback: PlayerUiState,
    floating: Boolean,
    onOpen: (serverId: String, bookId: String) -> Unit,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    val book = playback.audiobook ?: return
    val fraction = playback.durationMs.takeIf { it > 0 }
        ?.let { (playback.positionMs.toFloat() / it).coerceIn(0f, 1f) }

    val content: @Composable () -> Unit = {
        Column {
            // The mockup's floating pill mini-player (phone) actually omits this bar, but
            // the user asked to keep it on both the floating and full-width variants.
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(book.serverId, book.bookId) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(CoverShapeSmall)) {
                    book.coverUrl?.let {
                        AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(36.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    playback.currentChapterTitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close player")
                }
            }
        }
    }

    if (floating) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Surface(
                shape = Pill,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clip(Pill),
            ) { content() }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }
}
