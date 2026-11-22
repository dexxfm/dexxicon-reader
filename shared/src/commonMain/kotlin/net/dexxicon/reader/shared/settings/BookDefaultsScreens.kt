package net.dexxicon.reader.shared.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.datastore.PLAYBACK_SPEEDS
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.datastore.ReaderScrollMode
import net.dexxicon.reader.core.datastore.ReaderSwipeSensitivity
import net.dexxicon.reader.core.datastore.ReaderTheme
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.reader.proportionalScaleLabel
import net.dexxicon.reader.shared.reader.ReaderProportionalScaleControl
import net.dexxicon.reader.shared.reader.ReaderCustomThemeRow

/**
 * Phase 4 Stage H (issue #145) — Book Defaults, ported from native's
 * `feature/settings/BookDefaultsScreens.kt`. Audiobooks gets its own sub-screen; EPUB, comics
 * and PDF are condensed into one "Books" sub-screen, grouped by format, since between them
 * they're most of what a reading session touches. Every control here reads/writes the same
 * stores the readers and audiobook player use ([net.dexxicon.reader.core.datastore.ReaderPreferencesStore],
 * [net.dexxicon.reader.core.datastore.PlayerPreferencesStore]), so a change here or from
 * inside a book shows up in both places — on Android via the same DataStore file on disk
 * (see [ReaderPreferencesStore][net.dexxicon.reader.core.datastore.ReaderPreferencesStore]'s
 * doc comment on why constructing it more than once is safe), on iOS because there's only
 * ever one [AppContainer].
 */
@Composable
fun AudiobookDefaultsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { SettingsState(container, scope) }
    val prefs by state.playerPreferences.collectAsState()
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
                    onClick = { state.setDefaultSpeed(speed) },
                    label = { Text("${speed}×") },
                )
            }
        }
        LayoutSpacer(Modifier.height(12.dp))
        SettingRow(
            title = "Skip silence",
            subtitle = "Shorten long pauses in narration, for every audiobook",
        ) {
            Switch(checked = prefs.skipSilence, onCheckedChange = state::setSkipSilence)
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        DefaultsChips(
            "Skip forward",
            listOf(10, 15, 30, 45, 60),
            prefs.skipForwardSeconds,
            { "${it}s" },
        ) { state.setSkipForwardSeconds(it) }
        DefaultsChips(
            "Skip back",
            listOf(5, 10, 15, 30, 45),
            prefs.skipBackSeconds,
            { "${it}s" },
        ) { state.setSkipBackSeconds(it) }
        DefaultsChips(
            "Rewind on resume",
            listOf(0, 5, 10, 20, 30),
            prefs.smartRewindSeconds,
            { if (it == 0) "Off" else "${it}s" },
        ) { state.setSmartRewindSeconds(it) }
        Text(
            "How far to rewind when you come back to a paused book, to recover context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun BookDefaultsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { SettingsState(container, scope) }
    val prefs by state.readerPreferences.collectAsState()
    val update = state::updateReaderPreferences
    DefaultsScaffold("Books", onBack) {
        // Every one of these is one value shared by two or more formats already — shown
        // once, here, instead of repeated (and editable in two places) under each format.
        SettingsSubsectionTitle("General")
        Text(
            "Used across every format that supports it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        DefaultsChips(
            "Background",
            ReaderTheme.entries.filter { it != ReaderTheme.CUSTOM_1 && it != ReaderTheme.CUSTOM_2 },
            prefs.theme,
            ::readerThemeLabel,
        ) { theme ->
            update { it.copy(theme = theme) }
        }
        ReaderCustomThemeRow(
            title = "Custom 1",
            selected = prefs.theme == ReaderTheme.CUSTOM_1,
            background = prefs.custom1Background,
            foreground = prefs.custom1Foreground,
            onSelect = { update { it.copy(theme = ReaderTheme.CUSTOM_1) } },
            onBackgroundChange = { color -> update { it.copy(custom1Background = color) } },
            onForegroundChange = { color -> update { it.copy(custom1Foreground = color) } },
        )
        ReaderCustomThemeRow(
            title = "Custom 2",
            selected = prefs.theme == ReaderTheme.CUSTOM_2,
            background = prefs.custom2Background,
            foreground = prefs.custom2Foreground,
            onSelect = { update { it.copy(theme = ReaderTheme.CUSTOM_2) } },
            onBackgroundChange = { color -> update { it.copy(custom2Background = color) } },
            onForegroundChange = { color -> update { it.copy(custom2Foreground = color) } },
        )
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
        SettingRow(
            title = "Tap edges to turn pages",
            subtitle = if (prefs.comicSmartZoom) {
                "EPUB and comics — off for comics while Smart zoom is on"
            } else {
                "EPUB and comics"
            },
        ) {
            Switch(
                checked = prefs.tapNavigation,
                onCheckedChange = { on -> update { it.copy(tapNavigation = on) } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        SettingsSubsectionTitle("EPUB")
        ReaderProportionalScaleControl(
            title = "Font size",
            scale = prefs.fontScale,
            min = ReaderDisplayPreferences.MIN_FONT_SCALE,
            max = ReaderDisplayPreferences.MAX_FONT_SCALE,
            onDecreaseDescription = "Decrease font size",
            onIncreaseDescription = "Increase font size",
        ) { scale ->
            update { it.copy(fontScale = scale) }
        }
        ReaderProportionalScaleControl(
            title = "Line spacing",
            scale = prefs.lineSpacing ?: ReaderDisplayPreferences.DEFAULT_LINE_SPACING,
            min = ReaderDisplayPreferences.MIN_LINE_SPACING,
            max = ReaderDisplayPreferences.MAX_LINE_SPACING,
            onDecreaseDescription = "Decrease line spacing",
            onIncreaseDescription = "Increase line spacing",
            valueLabel = prefs.lineSpacing?.let { "${proportionalScaleLabel(it)}×" } ?: "Default",
        ) { spacing ->
            update { it.copy(lineSpacing = spacing) }
        }
        ReaderProportionalScaleControl(
            title = "Paragraph spacing",
            scale = prefs.paragraphSpacing ?: ReaderDisplayPreferences.DEFAULT_PARAGRAPH_SPACING,
            min = ReaderDisplayPreferences.MIN_PARAGRAPH_SPACING,
            max = ReaderDisplayPreferences.MAX_PARAGRAPH_SPACING,
            onDecreaseDescription = "Decrease paragraph spacing",
            onIncreaseDescription = "Increase paragraph spacing",
            valueLabel = prefs.paragraphSpacing?.let { "${proportionalScaleLabel(it)} rem" } ?: "Default",
        ) { spacing ->
            update { it.copy(paragraphSpacing = spacing) }
        }
        SettingRow(
            title = "Paragraph indent",
            subtitle = if (prefs.paragraphIndent == null) "Readium default" else null,
        ) {
            Switch(
                checked = prefs.paragraphIndent ?: false,
                onCheckedChange = { enabled -> update { it.copy(paragraphIndent = enabled) } },
            )
        }
        SettingRow(
            title = "Justification",
            subtitle = if (prefs.justification == null) "Readium default" else null,
        ) {
            Switch(
                checked = prefs.justification ?: false,
                onCheckedChange = { enabled -> update { it.copy(justification = enabled) } },
            )
        }
        SettingRow(
            title = "Hyphenation",
            subtitle = if (prefs.hyphenation == null) "Readium default" else null,
        ) {
            Switch(
                checked = prefs.hyphenation ?: false,
                onCheckedChange = { enabled -> update { it.copy(hyphenation = enabled) } },
            )
        }
        DefaultsChips("Page layout", ReaderPageLayout.entries, prefs.pageLayout, ::readerPageLayoutLabel) { layout ->
            update { it.copy(pageLayout = layout) }
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        SettingsSubsectionTitle("Comics")
        Text(
            "Also uses the page-turn swipe and tap-to-turn settings above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SettingRow(
            title = "Smart zoom (Experimental)",
            subtitle = "Step through each panel in order, like a guided view. Falls back " +
                "to the full page when panels can't be confidently detected.",
        ) {
            Switch(
                checked = prefs.comicSmartZoom,
                onCheckedChange = { on -> update { it.copy(comicSmartZoom = on) } },
            )
        }
        // issue #285 — same gap as between this screen's other switch rows.
        LayoutSpacer(Modifier.height(12.dp))
        SettingRow(
            title = "Right-to-left (manga)",
            subtitle = "Applied automatically to comics genre-tagged \"manga\" — this is the " +
                "default for everything else",
        ) {
            Switch(
                checked = prefs.comicRightToLeft,
                onCheckedChange = { on -> update { it.copy(comicRightToLeft = on) } },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        SettingsSubsectionTitle("PDF")
        Text(
            "Background, page fit, reading mode and page-turn swipe are set above. The page " +
                "colour applies to the margins and spacing — the PDF engine can't recolour the " +
                "page content itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DefaultsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(onBack)
                LayoutSpacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsSubsectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
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
    ReaderTheme.SEPIA_DARK -> "Sepia Dark"
    ReaderTheme.GREY -> "Grey"
    ReaderTheme.DARK -> "Black"
    ReaderTheme.MIDNIGHT -> "Midnight"
    ReaderTheme.NORD -> "Nord"
    ReaderTheme.CUSTOM_1 -> "Custom 1"
    ReaderTheme.CUSTOM_2 -> "Custom 2"
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
