package net.dexxicon.reader.shared.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.datastore.PLAYBACK_SPEEDS
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.designsystem.component.PillButton
import net.dexxicon.reader.core.designsystem.theme.CoverShapeLarge
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.model.ResumeConflict
import kotlin.math.abs

private val SLEEP_OPTIONS = listOf(
    "Off" to null,
    "15 minutes" to 15L * 60_000,
    "30 minutes" to 30L * 60_000,
    "45 minutes" to 45L * 60_000,
    "1 hour" to 60L * 60_000,
)

/**
 * Phase 1 of the shared-reader-chrome redesign (issue #183) — ported from Android's
 * `feature/player/PlayerScreen.kt`, minus the pieces that only make sense on Android (Google
 * Cast, the system audio-output picker — no iOS equivalent exists for either, see
 * `AudiobookPlaybackController`'s own doc comment on route-following) and minus the "audio
 * options" sheet (skip-silence/skip-interval/rewind tuning), which iOS's playback engine
 * doesn't yet support changing per-book — a real, deliberate gap for now, not an oversight.
 *
 * Takes [state]/[actions] directly rather than reading a ViewModel — the caller (each
 * platform's own thin wrapper) is responsible for sourcing both from
 * [net.dexxicon.reader.shared.di.AppContainer]'s `playerState`/`playerActions`, exactly like
 * [MiniPlayer] already does for its own narrower slice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiSnapshot?,
    actions: PlayerActions,
    onBack: () -> Unit,
    /** Set only while resolving the book/starting playback fails (e.g. no audio acquisition,
     * a network error) — matches Android's old `PlayerViewModel.screen.error`. `null` (the
     * common case, [state] simply not being loaded *yet*) shows the loading spinner instead. */
    error: String? = null,
    /** issue #216 — non-null when the current/about-to-resume position and the server's
     *  disagree meaningfully; playback is held (not autoplaying) until [onResumeConflict]
     *  resolves it. */
    resumeConflict: ResumeConflict? = null,
    onResumeConflict: (useServerPosition: Boolean) -> Unit = {},
) {
    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                // No title here — it's already on the cover art and repeated again in
                // TrackInfo below it, so a third copy in the app bar is just noise (#116).
                title = {},
                navigationIcon = { BackPill(onBack) },
                actions = {
                    if (state != null && state.chapters.isNotEmpty()) {
                        IconButton(onClick = { showChapters = true }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Chapters")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (error != null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        } else if (state == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            NowPlayingContent(
                modifier = Modifier.padding(padding),
                state = state,
                onPlayPause = actions.playPause,
                onSkipForward = actions.skipForward,
                onSkipBack = actions.skipBack,
                onNextChapter = actions.nextChapter,
                onPrevChapter = actions.previousChapter,
                onSeek = actions.seekTo,
                onSpeed = { showSpeed = true },
                onSleep = { showSleep = true },
            )
        }
    }

    if (showChapters && state != null) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(state.chapters) { index, chapter ->
                    val current = index == state.currentChapterIndex
                    TextButton(
                        onClick = { actions.seekToChapter(index); showChapters = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${index + 1}.  ${chapter.title}",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (showSpeed && state != null) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Playback speed", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                PLAYBACK_SPEEDS.forEach { speed ->
                    TextButton(
                        onClick = { actions.setSpeed(speed); showSpeed = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${speed}×",
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            color = if (abs(speed - state.speed) < 0.01f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSleep && state != null) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Sleep timer", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                SLEEP_OPTIONS.forEach { (label, ms) ->
                    TextButton(
                        onClick = { actions.setSleepTimer(ms); showSleep = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
                TextButton(
                    onClick = { actions.setSleepTimerEndOfChapter(); showSleep = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "End of current chapter",
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = if (state.sleepAtChapterEnd) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    // issue #216 — a blocking choice, not a dismissible banner: silently picking a side here
    // is exactly the bug (a stale cached position kept winning and got pushed right back to
    // the server), so this holds playback until the user actually decides.
    if (resumeConflict != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Resume from where you left off?") },
            text = {
                Text(
                    "The server has this at ${formatTime(resumeConflict.serverPositionMs)}, " +
                        "but this device is at ${formatTime(resumeConflict.localPositionMs)}.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onResumeConflict(true) }) {
                    Text("Resume at ${formatTime(resumeConflict.serverPositionMs)}")
                }
            },
            dismissButton = {
                TextButton(onClick = { onResumeConflict(false) }) {
                    Text("Keep ${formatTime(resumeConflict.localPositionMs)}")
                }
            },
        )
    }
}

@Composable
private fun NowPlayingContent(
    modifier: Modifier,
    state: PlayerUiSnapshot,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Cover on the left, controls on the right — only for a genuinely wide area
        // (tablet / phone landscape). A near-square area (an unfolded foldable) stays
        // stacked, otherwise the controls get crushed into a strip beside the cover.
        val sideBySide = maxWidth >= 720.dp && maxWidth > maxHeight * 1.4f
        // Keep the stacked cover from crowding out the controls on shorter areas.
        val stackedCover = minOf(maxWidth * 0.7f, maxHeight * 0.42f, 360.dp)
        // Inner content is at least the viewport minus its 24dp padding, so it stays
        // centered by SpaceEvenly when it fits and only scrolls when it genuinely can't.
        val minContentHeight = (maxHeight - 48.dp).coerceAtLeast(0.dp)

        val controls: @Composable ColumnScope.(centered: Boolean) -> Unit = { centered ->
            TrackInfo(state, centered = centered)
            // issue #190 — a playback-time failure (bad stream, network, unsupported format)
            // used to be entirely silent: this book's own cover/title/transport controls all
            // render regardless (see PlayerUiSnapshot.playbackError's own doc comment for why),
            // so the error shows alongside them rather than replacing the whole screen the way
            // PlayerScreen's own top-level `error` param does for a load failure.
            state.playbackError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Scrubber(state, onSeek)
            TransportControls(state, onPlayPause, onSkipForward, onSkipBack, onNextChapter, onPrevChapter)
            SecondaryControls(state, onSpeed, onSleep)
        }

        if (sideBySide) {
            Row(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    CoverArt(
                        coverUrl = state.coverUrl,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true),
                    )
                }
                // Scrolls only if a short landscape area can't fit the controls.
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = minContentHeight).widthIn(max = 520.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        controls(false)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minContentHeight).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CoverArt(
                        coverUrl = state.coverUrl,
                        modifier = Modifier.size(stackedCover),
                    )
                    controls(true)
                }
            }
        }
    }
}

@Composable
private fun CoverArt(coverUrl: String?, modifier: Modifier) {
    Box(
        modifier
            .clip(CoverShapeLarge)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        coverUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TrackInfo(state: PlayerUiSnapshot, centered: Boolean) {
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            state.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!state.author.isNullOrBlank()) {
            Text(
                state.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.narrator.isNullOrBlank()) {
            Text(
                "Narrated by ${state.narrator}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.currentChapterTitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Scrubber(state: PlayerUiSnapshot, onSeek: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        var scrubbing by remember { mutableStateOf<Float?>(null) }
        val duration = state.durationMs.coerceAtLeast(1L)
        // issue #205 — durationMs is still 0 before playback metadata has loaded (or stays 0
        // forever on a connection error). Slider clamps `value` into `valueRange`, so a real,
        // possibly large resumed positionMs against that 1ms placeholder range rendered as a
        // full bar instead of empty. Position is meaningless as a fraction of an unknown total,
        // so show 0 until a real duration arrives.
        val durationKnown = state.durationMs > 0L
        val position = if (durationKnown) (scrubbing ?: state.positionMs.toFloat()) else 0f
        Slider(
            value = position,
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { onSeek(it.toLong()) }
                scrubbing = null
            },
            valueRange = 0f..duration.toFloat(),
            enabled = durationKnown,
            modifier = Modifier.semantics {
                contentDescription = "Playback position"
                stateDescription = "${formatTime(position.toLong())} of ${formatTime(duration)}"
            },
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(position.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransportControls(
    state: PlayerUiSnapshot,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevChapter) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter")
        }
        IconButton(onClick = onSkipBack) {
            Icon(Icons.Filled.Replay, contentDescription = "Back 15 seconds")
        }
        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp), shape = Pill) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = onSkipForward) {
            Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds")
        }
        IconButton(onClick = onNextChapter) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter")
        }
    }
}

@Composable
private fun SecondaryControls(state: PlayerUiSnapshot, onSpeed: () -> Unit, onSleep: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        Alignment.CenterVertically,
    ) {
        PillButton(icon = Icons.Filled.Speed, label = "${state.speed}×", onClick = onSpeed)
        val sleepOn = state.sleepTimerEndsAtEpochMs != null || state.sleepAtChapterEnd
        PillButton(
            icon = Icons.Filled.Bedtime,
            label = if (sleepOn) "Sleep on" else "Sleep timer",
            onClick = onSleep,
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds / 60) % 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
