package net.dexxicon.reader.shared.reader.comic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.datastore.ReaderSwipeSensitivity
import net.dexxicon.reader.core.designsystem.component.BackPill

/**
 * Phase 4 of the shared-reader-chrome redesign (issue #183) — the comic reader's chrome,
 * ported from `feature/reader-comic`'s original `ComicReaderScreen.kt`/`ReaderContent`.
 * Narrower than EPUB/PDF's: no bookmarks/TOC/display-theme settings at all — just a title
 * bar, a page slider, and three settings (swipe sensitivity, tap-navigation, right-to-left).
 *
 * Deliberately excludes Android's "Smart zoom" (guided-panel-view) setting and its whole
 * panel-stepping/zoom-transform machinery — that's fused to Android `Bitmap`/`PhotoView`
 * internals via reflection (see the original screen's own doc comments) and isn't a portable
 * feature. [extraSettings] is the escape hatch: Android appends its Smart Zoom row there so
 * the one settings sheet still shows all four options, while iOS passes nothing.
 *
 * [tapNavigationEnabled]/[tapNavigationDisabledReason] exist only because Android disables
 * tap-navigation while Smart Zoom is on (edge-tap zones stop lining up with a zoomed panel) —
 * generic params here so `:shared` never has to know what "Smart zoom" is; iOS leaves both
 * at their default (always enabled, no caveat).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    state: ComicReaderUiState,
    onBack: () -> Unit,
    chromeVisible: Boolean = true,
    swipeSensitivity: ReaderSwipeSensitivity = ReaderSwipeSensitivity.MEDIUM,
    onSwipeSensitivity: (ReaderSwipeSensitivity) -> Unit = {},
    tapNavigation: Boolean = true,
    onToggleTapNavigation: (Boolean) -> Unit = {},
    tapNavigationEnabled: Boolean = true,
    tapNavigationDisabledReason: String? = null,
    rightToLeft: Boolean = false,
    onToggleRightToLeft: (Boolean) -> Unit = {},
    currentPage: Int = 1,
    onGoToPage: (Int) -> Unit = {},
    extraSettings: @Composable ColumnScope.() -> Unit = {},
    readerContent: @Composable () -> Unit = {},
) {
    when (state) {
        is ComicReaderUiState.Loading -> Center { CircularProgressIndicator() }
        is ComicReaderUiState.Error -> Center {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        is ComicReaderUiState.Ready -> ReaderContent(
            state = state,
            onBack = onBack,
            chromeVisible = chromeVisible,
            swipeSensitivity = swipeSensitivity,
            onSwipeSensitivity = onSwipeSensitivity,
            tapNavigation = tapNavigation,
            onToggleTapNavigation = onToggleTapNavigation,
            tapNavigationEnabled = tapNavigationEnabled,
            tapNavigationDisabledReason = tapNavigationDisabledReason,
            rightToLeft = rightToLeft,
            onToggleRightToLeft = onToggleRightToLeft,
            currentPage = currentPage,
            onGoToPage = onGoToPage,
            extraSettings = extraSettings,
            readerContent = readerContent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: ComicReaderUiState.Ready,
    onBack: () -> Unit,
    chromeVisible: Boolean,
    swipeSensitivity: ReaderSwipeSensitivity,
    onSwipeSensitivity: (ReaderSwipeSensitivity) -> Unit,
    tapNavigation: Boolean,
    onToggleTapNavigation: (Boolean) -> Unit,
    tapNavigationEnabled: Boolean,
    tapNavigationDisabledReason: String?,
    rightToLeft: Boolean,
    onToggleRightToLeft: (Boolean) -> Unit,
    currentPage: Int,
    onGoToPage: (Int) -> Unit,
    extraSettings: @Composable ColumnScope.() -> Unit,
    readerContent: @Composable () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    title = { Text(state.title, maxLines = 1) },
                    navigationIcon = { BackPill(onBack) },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Reading settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (chromeVisible && state.pageCount > 1) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Page $currentPage of ${state.pageCount}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = currentPage.coerceIn(1, state.pageCount).toFloat(),
                        onValueChange = { v -> onGoToPage(v.toInt().coerceIn(1, state.pageCount)) },
                        valueRange = 1f..state.pageCount.toFloat(),
                        modifier = Modifier.semantics {
                            contentDescription = "Page slider"
                            stateDescription = "Page $currentPage of ${state.pageCount}"
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            readerContent()
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ComicSettings(
                swipeSensitivity = swipeSensitivity,
                onSwipeSensitivity = onSwipeSensitivity,
                tapNavigation = tapNavigation,
                onToggleTapNavigation = onToggleTapNavigation,
                tapNavigationEnabled = tapNavigationEnabled,
                tapNavigationDisabledReason = tapNavigationDisabledReason,
                rightToLeft = rightToLeft,
                onToggleRightToLeft = onToggleRightToLeft,
                extraSettings = extraSettings,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicSettings(
    swipeSensitivity: ReaderSwipeSensitivity,
    onSwipeSensitivity: (ReaderSwipeSensitivity) -> Unit,
    tapNavigation: Boolean,
    onToggleTapNavigation: (Boolean) -> Unit,
    tapNavigationEnabled: Boolean,
    tapNavigationDisabledReason: String?,
    rightToLeft: Boolean,
    onToggleRightToLeft: (Boolean) -> Unit,
    extraSettings: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .navigationBarsPadding(),
    ) {
        Text("Page-turn swipe", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderSwipeSensitivity.entries.forEach { s ->
                FilterChip(
                    selected = swipeSensitivity == s,
                    onClick = { onSwipeSensitivity(s) },
                    label = { Text(s.label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Tap edges to turn pages",
                    color = if (tapNavigationEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
                if (tapNavigationDisabledReason != null) {
                    Text(
                        tapNavigationDisabledReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = tapNavigation && tapNavigationEnabled,
                onCheckedChange = onToggleTapNavigation,
                enabled = tapNavigationEnabled,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Right-to-left (manga)")
                Text(
                    "On automatically for this book's genre — override just for now",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rightToLeft, onCheckedChange = onToggleRightToLeft)
        }
        extraSettings()
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
