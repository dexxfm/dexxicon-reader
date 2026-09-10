package net.dexxicon.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.datastore.PLAYBACK_SPEEDS
import net.dexxicon.reader.core.reader.ReaderFitMode
import net.dexxicon.reader.core.reader.ReaderPageLayout
import net.dexxicon.reader.core.reader.ReaderScrollMode
import net.dexxicon.reader.core.reader.ReaderSwipeSensitivity
import net.dexxicon.reader.core.reader.ReaderTheme

/**
 * Book Defaults — Audiobooks gets its own sub-screen; EPUB, comics and PDF are condensed
 * into one "Books" sub-screen, grouped by format, since between them they're most of what
 * a reading session touches. Every control here reads/writes the same stores the readers
 * and audiobook player use (`ReaderPreferencesStore`, `PlayerPreferencesStore`), so a
 * change here or from inside a book shows up in both places.
 */
@Composable
fun AudiobookDefaultsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.playerPreferences.collectAsStateWithLifecycle()
    DefaultsScaffold("Audiobooks", onBack) {
        Text("Default speed", style = MaterialTheme.typography.bodyMedium)
        Text(
            "The speed every audiobook starts at. Changing it here or in the player's own " +
                "speed control sets the same default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PLAYBACK_SPEEDS.forEach { speed ->
                FilterChip(
                    selected = prefs.defaultSpeed == speed,
                    onClick = { viewModel.setDefaultSpeed(speed) },
                    label = { Text("${speed}×") },
                )
            }
        }
        LayoutSpacer(Modifier.height(12.dp))
        SettingRow(
            title = "Skip silence",
            subtitle = "Shorten long pauses in narration, for every audiobook",
        ) {
            Switch(checked = prefs.skipSilence, onCheckedChange = viewModel::setSkipSilence)
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        DefaultsChips(
            "Skip forward",
            listOf(10, 15, 30, 45, 60),
            prefs.skipForwardSeconds,
            { "${it}s" },
        ) { viewModel.setSkipForwardSeconds(it) }
        DefaultsChips(
            "Skip back",
            listOf(5, 10, 15, 30, 45),
            prefs.skipBackSeconds,
            { "${it}s" },
        ) { viewModel.setSkipBackSeconds(it) }
        DefaultsChips(
            "Rewind on resume",
            listOf(0, 5, 10, 20, 30),
            prefs.smartRewindSeconds,
            { if (it == 0) "Off" else "${it}s" },
        ) { viewModel.setSmartRewindSeconds(it) }
        Text(
            "How far to rewind when you come back to a paused book, to recover context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun BookDefaultsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.readerPreferences.collectAsStateWithLifecycle()
    val update = viewModel::updateReaderPreferences
    DefaultsScaffold("Books", onBack) {
        // Every one of these is one value shared by two or more formats already — shown
        // once, here, instead of repeated (and editable in two places) under each format.
        SectionTitle("General")
        Text(
            "Used across every format that supports it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        DefaultsChips("Background", ReaderTheme.entries, prefs.theme, ::readerThemeLabel) { theme ->
            update { it.copy(theme = theme) }
        }
        Text(
            "EPUB and PDF.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DefaultsChips("Page fit", ReaderFitMode.entries, prefs.fitMode, ::readerFitLabel) { fit ->
            update { it.copy(fitMode = fit) }
        }
        Text(
            "EPUB honours all four; the PDF engine only distinguishes fit-page from fit-width.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DefaultsChips(
            "Reading mode",
            listOf(ReaderScrollMode.PAGED, ReaderScrollMode.SCROLL),
            if (prefs.scrollMode == ReaderScrollMode.PAGED) ReaderScrollMode.PAGED else ReaderScrollMode.SCROLL,
            ::readerScrollModeLabel,
        ) { mode -> update { it.copy(scrollMode = mode) } }
        Text(
            "EPUB and PDF.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DefaultsChips(
            "Page-turn swipe",
            ReaderSwipeSensitivity.entries,
            prefs.swipeSensitivity,
            { it.label },
        ) { s -> update { p -> p.copy(swipeSensitivity = s) } }
        Text(
            "How far you drag before the page turns. Higher is a lighter flick; lower needs a " +
                "deliberate swipe. Comics and PDF.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LayoutSpacer(Modifier.height(12.dp))
        SettingRow(title = "Tap edges to turn pages", subtitle = "EPUB and comics") {
            Switch(
                checked = prefs.tapNavigation,
                onCheckedChange = { on -> update { it.copy(tapNavigation = on) } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        SectionTitle("EPUB")
        Text("Text size", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = prefs.fontScale.toFloat(),
            onValueChange = { v -> update { it.copy(fontScale = v.toDouble()) } },
            valueRange = 0.6f..2.4f,
            steps = 8,
        )
        DefaultsChips("Page layout", ReaderPageLayout.entries, prefs.pageLayout, ::readerPageLayoutLabel) { layout ->
            update { it.copy(pageLayout = layout) }
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        SectionTitle("Comics")
        Text(
            "Also uses the page-turn swipe and tap-to-turn settings above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SettingRow(
            title = "Smart zoom",
            subtitle = "Step through each panel in order, like a guided view. Falls back " +
                "to the full page when panels can't be confidently detected.",
        ) {
            Switch(
                checked = prefs.comicSmartZoom,
                onCheckedChange = { on -> update { it.copy(comicSmartZoom = on) } },
            )
        }
        SettingRow(
            title = "Right-to-left (manga)",
            subtitle = "Panel order and page turns run right to left",
        ) {
            Switch(
                checked = prefs.comicRightToLeft,
                onCheckedChange = { on -> update { it.copy(comicRightToLeft = on) } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        SectionTitle("PDF")
        Text(
            "Background, page fit, reading mode and page-turn swipe are set above. The page " +
                "colour applies to the margins and spacing — the PDF engine can't recolour the " +
                "page content itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp)
                .padding(20.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> DefaultsChips(
    title: String,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
    FlowRow(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                label = { Text(label(entry)) },
            )
        }
    }
}

private fun readerThemeLabel(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.SYSTEM -> "System"
    ReaderTheme.LIGHT -> "White"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.GREY -> "Grey"
    ReaderTheme.DARK -> "Black"
}

private fun readerFitLabel(fit: ReaderFitMode): String = when (fit) {
    ReaderFitMode.PAGE_FIT -> "Fit"
    ReaderFitMode.PAGE_WIDTH -> "Width"
    ReaderFitMode.PAGE_HEIGHT -> "Height"
    ReaderFitMode.ACTUAL_SIZE -> "Actual size"
}

private fun readerPageLayoutLabel(layout: ReaderPageLayout): String = when (layout) {
    ReaderPageLayout.AUTO -> "Auto"
    ReaderPageLayout.SINGLE -> "Single"
    ReaderPageLayout.DOUBLE -> "Two-page"
}

private fun readerScrollModeLabel(mode: ReaderScrollMode): String = when (mode) {
    ReaderScrollMode.PAGED -> "Paged"
    ReaderScrollMode.SCROLL -> "Scroll"
    ReaderScrollMode.CONTINUOUS -> "Continuous"
}
