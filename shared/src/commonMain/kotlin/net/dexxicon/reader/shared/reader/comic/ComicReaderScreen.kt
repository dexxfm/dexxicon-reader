package net.dexxicon.reader.shared.reader.comic

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderSwipeSensitivity
import net.dexxicon.reader.core.datastore.ReaderTheme
import net.dexxicon.reader.core.designsystem.component.BackPill

/**
 * Phase 4 of the shared-reader-chrome redesign (issue #183) — the comic reader's chrome,
 * ported from `feature/reader-comic`'s original `ComicReaderScreen.kt`/`ReaderContent`.
 * Narrower than EPUB/PDF's: no bookmarks/TOC at all — just a title bar, a page slider, and a
 * settings sheet (swipe sensitivity, tap-navigation, right-to-left, page fit, background).
 *
 * [preferences]/[onUpdatePreferences] follow [net.dexxicon.reader.shared.reader.pdf
 * .PdfReaderScreen]'s own shape exactly — [ReaderFitMode]/[ReaderTheme] mean the same thing
 * they do there (fit-page vs. fit-width; a background colour that only shows in the letterbox
 * margins, since neither engine can recolour an opaque bitmap page) rather than anything
 * comic-specific, so the settings sheet reuses PDF's own two-option "Fit"/"Width" reduction.
 *
 * Deliberately excludes Android's "Smart zoom" (guided-panel-view) setting and its whole
 * panel-stepping/zoom-transform machinery — that's fused to Android `Bitmap`/`PhotoView`
 * internals via reflection (see the original screen's own doc comments) and isn't a portable
 * feature. [extraSettings] is the escape hatch: Android appends its Smart Zoom row there so
 * the one settings sheet still shows every option, while iOS passes nothing.
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
    preferences: ReaderDisplayPreferences = ReaderDisplayPreferences(),
    tapNavigationEnabled: Boolean = true,
    tapNavigationDisabledReason: String? = null,
    currentPage: Int = 1,
    onGoToPage: (Int) -> Unit = {},
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit = {},
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
            preferences = preferences,
            tapNavigationEnabled = tapNavigationEnabled,
            tapNavigationDisabledReason = tapNavigationDisabledReason,
            currentPage = currentPage,
            onGoToPage = onGoToPage,
            onUpdatePreferences = onUpdatePreferences,
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
    preferences: ReaderDisplayPreferences,
    tapNavigationEnabled: Boolean,
    tapNavigationDisabledReason: String?,
    currentPage: Int,
    onGoToPage: (Int) -> Unit,
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    extraSettings: @Composable ColumnScope.() -> Unit,
    readerContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val darkTheme = isSystemInDarkTheme()

    Scaffold(
        containerColor = preferences.theme.comicSurfaceColor(darkTheme),
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
                preferences = preferences,
                onChange = { transform -> scope.launch { onUpdatePreferences(transform) } },
                tapNavigationEnabled = tapNavigationEnabled,
                tapNavigationDisabledReason = tapNavigationDisabledReason,
                extraSettings = extraSettings,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicSettings(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    tapNavigationEnabled: Boolean,
    tapNavigationDisabledReason: String?,
    extraSettings: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .navigationBarsPadding(),
    ) {
        Text("Background", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderTheme.entries.forEach { theme ->
                FilterChip(
                    selected = preferences.theme == theme,
                    onClick = { onChange { it.copy(theme = theme) } },
                    label = { Text(comicThemeLabel(theme)) },
                )
            }
        }
        Text(
            "Only shows around the page — comic art can't be recoloured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Page fit", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Same two-option reduction as PDF's own "Page fit" — the underlying engines
            // don't distinguish anything finer than fit-page vs. fit-width.
            val fitWidth = preferences.fitMode == ReaderFitMode.PAGE_WIDTH
            FilterChip(
                selected = !fitWidth,
                onClick = { onChange { it.copy(fitMode = ReaderFitMode.PAGE_FIT) } },
                label = { Text("Fit") },
            )
            FilterChip(
                selected = fitWidth,
                onClick = { onChange { it.copy(fitMode = ReaderFitMode.PAGE_WIDTH) } },
                label = { Text("Width") },
            )
        }

        Text("Page-turn swipe", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            net.dexxicon.reader.core.datastore.ReaderSwipeSensitivity.entries.forEach { s ->
                FilterChip(
                    selected = preferences.swipeSensitivity == s,
                    onClick = { onChange { it.copy(swipeSensitivity = s) } },
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
                checked = preferences.tapNavigation && tapNavigationEnabled,
                onCheckedChange = { enabled -> onChange { it.copy(tapNavigation = enabled) } },
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
            Switch(
                checked = preferences.comicRightToLeft,
                onCheckedChange = { enabled -> onChange { it.copy(comicRightToLeft = enabled) } },
            )
        }
        extraSettings()
    }
}

private fun comicThemeLabel(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.SYSTEM -> "System"
    ReaderTheme.LIGHT -> "White"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.GREY -> "Grey"
    ReaderTheme.DARK -> "Black"
}

/** Same shape as [net.dexxicon.reader.shared.reader.pdf.PdfReaderScreen]'s own
 * `pdfSurfaceColor` — duplicated per that function's own doc comment (a pure Compose mapping
 * with no engine dependency belongs beside its own reader, not in a shared-utilities file). */
private fun ReaderTheme.comicSurfaceColor(systemInDark: Boolean): Color = when (this) {
    ReaderTheme.SYSTEM -> if (systemInDark) Color(0xFF101114) else Color(0xFFF6F6F6)
    ReaderTheme.LIGHT -> Color(0xFFF6F6F6)
    ReaderTheme.SEPIA -> Color(0xFFEFE6D3)
    ReaderTheme.GREY -> Color(0xFF3A3D42)
    ReaderTheme.DARK -> Color(0xFF101114)
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
