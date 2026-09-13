package net.dexxicon.reader.shared.player

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

/**
 * Phase 4 Stage I (issue #146) — ported from native's `app/ui/MiniPlayer.kt`, backed by
 * [NowPlaying] instead of the Android-only `PlayerUiState` so it renders identically on both
 * platforms. Always the floating translucent pill, at every width (the mockup's own fold/tablet
 * turn shows a full-width bar once the rail replaces the bottom nav; overridden on request to
 * keep the pill treatment everywhere, same call native's version made).
 */
@Composable
fun MiniPlayer(
    playback: NowPlaying,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fraction = playback.durationMs.takeIf { it > 0 }
        ?.let { (playback.positionMs.toFloat() / it).coerceIn(0f, 1f) }

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Surface(
            shape = Pill,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clip(Pill),
        ) {
            Column {
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(CoverShapeSmall)) {
                        playback.coverUrl?.let {
                            AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(36.dp))
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            playback.title,
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
    }
}
