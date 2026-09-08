package net.dexxicon.reader.feature.player

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.util.concurrent.TimeUnit

private val SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f, 3.0f)
private val SLEEP_OPTIONS = listOf(
    "Off" to null,
    "15 minutes" to 15L * 60_000,
    "30 minutes" to 30L * 60_000,
    "45 minutes" to 45L * 60_000,
    "1 hour" to 60L * 60_000,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showAudioOptions by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playback.audiobook?.title.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAudioOptions = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Audio options")
                    }
                    if (playback.audiobook?.chapters?.isNotEmpty() == true) {
                        IconButton(onClick = { showChapters = true }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Chapters")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            screen.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            screen.error != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(screen.error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            else -> NowPlaying(
                modifier = Modifier.padding(padding),
                playback = playback,
                onPlayPause = viewModel::playPause,
                onSkipForward = viewModel::skipForward,
                onSkipBack = viewModel::skipBack,
                onNextChapter = viewModel::nextChapter,
                onPrevChapter = viewModel::previousChapter,
                onSeek = viewModel::seekTo,
                onSpeed = { showSpeed = true },
                onSleep = { showSleep = true },
            )
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(playback.audiobook?.chapters.orEmpty()) { index, chapter ->
                    val current = index == playback.currentChapterIndex
                    TextButton(
                        onClick = { viewModel.seekToChapter(index); showChapters = false },
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

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Playback speed", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                SPEEDS.forEach { speed ->
                    TextButton(
                        onClick = { viewModel.setSpeed(speed); showSpeed = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${speed}×",
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            color = if (kotlin.math.abs(speed - playback.speed) < 0.01f) {
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

    if (showSleep) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Sleep timer", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                SLEEP_OPTIONS.forEach { (label, ms) ->
                    TextButton(
                        onClick = { viewModel.setSleepTimer(ms); showSleep = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
                TextButton(
                    onClick = { viewModel.setSleepTimerEndOfChapter(); showSleep = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "End of current chapter",
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        color = if (playback.sleepAtChapterEnd) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    if (showAudioOptions) {
        ModalBottomSheet(onDismissRequest = { showAudioOptions = false }) {
            AudioOptions(
                options = playback.options,
                onSkipSilence = viewModel::setSkipSilence,
                onSkipForward = viewModel::setSkipForwardSeconds,
                onSkipBack = viewModel::setSkipBackSeconds,
                onSmartRewind = viewModel::setSmartRewindSeconds,
            )
        }
    }
}

@Composable
private fun AudioOptions(
    options: net.dexxicon.reader.core.datastore.PlayerPreferences,
    onSkipSilence: (Boolean) -> Unit,
    onSkipForward: (Int) -> Unit,
    onSkipBack: (Int) -> Unit,
    onSmartRewind: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
        Text("Audio options", style = MaterialTheme.typography.titleMedium)

        AudioOutputRow(Modifier.padding(top = 16.dp))

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Skip silence", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Shorten long pauses in narration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = options.skipSilence, onCheckedChange = onSkipSilence)
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        ChipRow(
            label = "Skip forward",
            values = listOf(10, 15, 30, 45, 60),
            selected = options.skipForwardSeconds,
            format = { "${it}s" },
            onSelect = onSkipForward,
        )
        ChipRow(
            label = "Skip back",
            values = listOf(5, 10, 15, 30, 45),
            selected = options.skipBackSeconds,
            format = { "${it}s" },
            onSelect = onSkipBack,
            modifier = Modifier.padding(top = 12.dp),
        )
        ChipRow(
            label = "Rewind on resume",
            values = listOf(0, 5, 10, 20, 30),
            selected = options.smartRewindSeconds,
            format = { if (it == 0) "Off" else "${it}s" },
            onSelect = onSmartRewind,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Shows where audio is currently playing and opens the system output switcher (speaker,
 * paired Bluetooth, wired headset, Cast targets). Android exposes no supported way to pin
 * media output from the app, so switching is delegated to the OS panel.
 */
@Composable
private fun AudioOutputRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    var output by remember { mutableStateOf(currentAudioOutput(audioManager)) }

    DisposableEffect(audioManager) {
        val am = audioManager ?: return@DisposableEffect onDispose {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                output = currentAudioOutput(am)
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                output = currentAudioOutput(am)
            }
        }
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(output.icon, contentDescription = null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text("Playing on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(output.label, style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = {
            runCatching {
                // Settings.Panel.ACTION_MEDIA_OUTPUT — the system output switcher (API 29+).
                context.startActivity(
                    Intent("android.settings.panel.action.MEDIA_OUTPUT")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text("Change") }
    }
}

private data class AudioOutput(val label: String, val icon: ImageVector)

@Suppress("DEPRECATION") // isBluetoothA2dpOn / isWiredHeadsetOn: still the simplest "which route is live" read
private fun currentAudioOutput(am: AudioManager?): AudioOutput {
    if (am == null) return AudioOutput("Phone speaker", Icons.Filled.Speaker)
    val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    fun device(vararg types: Int) = outputs.firstOrNull { it.type in types }

    val bluetooth = device(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
    val wired = device(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )
    return when {
        am.isBluetoothA2dpOn && bluetooth != null ->
            AudioOutput(
                bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth",
                Icons.Filled.Bluetooth,
            )
        am.isWiredHeadsetOn && wired != null ->
            AudioOutput("Headphones", Icons.Filled.Headphones)
        else -> AudioOutput("Phone speaker", Icons.Filled.Speaker)
    }
}

@Composable
private fun ChipRow(
    label: String,
    values: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(format(value)) },
                )
            }
        }
    }
}

@Composable
private fun NowPlaying(
    modifier: Modifier,
    playback: net.dexxicon.reader.core.media.PlayerUiState,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevChapter: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
) {
    val book = playback.audiobook
    val duration = playback.durationMs.takeIf { it > 0 } ?: book?.durationMs ?: 0L

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
            TrackInfo(playback, centered = centered)
            Scrubber(playback, duration, onSeek)
            TransportControls(playback, onPlayPause, onSkipForward, onSkipBack, onNextChapter, onPrevChapter)
            SecondaryControls(playback, onSpeed, onSleep)
        }

        if (sideBySide) {
            Row(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    CoverArt(
                        coverUrl = book?.coverUrl,
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
                        coverUrl = book?.coverUrl,
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
            .clip(RoundedCornerShape(16.dp))
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
private fun TrackInfo(playback: net.dexxicon.reader.core.media.PlayerUiState, centered: Boolean) {
    val book = playback.audiobook
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            book?.title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!book?.author.isNullOrBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        playback.currentChapterTitle?.let {
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
private fun Scrubber(
    playback: net.dexxicon.reader.core.media.PlayerUiState,
    duration: Long,
    onSeek: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        var scrubbing by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = scrubbing ?: playback.positionMs.toFloat(),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { onSeek(it.toLong()) }
                scrubbing = null
            },
            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
            modifier = Modifier.semantics {
                contentDescription = "Playback position"
                stateDescription = "${formatTime((scrubbing ?: playback.positionMs.toFloat()).toLong())} of ${formatTime(duration)}"
            },
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime((scrubbing ?: playback.positionMs.toFloat()).toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransportControls(
    playback: net.dexxicon.reader.core.media.PlayerUiState,
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
        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
            Icon(
                if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
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
private fun SecondaryControls(
    playback: net.dexxicon.reader.core.media.PlayerUiState,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        TextButton(onClick = onSpeed) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  ${playback.speed}×", maxLines = 1, softWrap = false)
        }
        val sleepOn = playback.sleepTimerEndsAt != null || playback.sleepAtChapterEnd
        TextButton(onClick = onSleep) {
            Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (sleepOn) "  Sleep on" else "  Sleep timer", maxLines = 1, softWrap = false)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = TimeUnit.SECONDS.toHours(totalSeconds)
    val m = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
