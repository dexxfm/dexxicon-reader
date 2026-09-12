package net.dexxicon.reader.shared.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.round
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.designsystem.component.FormatLegend
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.shared.di.AppContainer

private const val GB = 1024L * 1024 * 1024

/** Storage-limit presets. `null` = no limit. */
private val DOWNLOAD_LIMIT_OPTIONS: List<Pair<String, Long?>> = listOf(
    "1 GB" to 1 * GB,
    "5 GB" to 5 * GB,
    "10 GB" to 10 * GB,
    "20 GB" to 20 * GB,
    "50 GB" to 50 * GB,
    "None" to null,
)

private val CoverTapAction.label: String
    get() = when (this) {
        CoverTapAction.OPEN_DETAILS -> "Open book details"
        CoverTapAction.OPEN_BOOK -> "Open book"
    }

/** Kotlin/Native has no `String.format` — same portability trap
 * [net.dexxicon.reader.shared.catalog.BookDetailContent]'s `formatFileSize` hit (issue #126). */
private fun wholeNumber(value: Double): String = round(value).toLong().toString()

private fun oneDecimal(value: Double): String {
    val rounded = round(value * 10) / 10
    val whole = rounded.toLong()
    val frac = abs((rounded * 10).toLong() % 10)
    return "$whole.$frac"
}

private fun formatGigabytes(bytes: Long): String {
    val gb = bytes / GB.toDouble()
    return when {
        bytes < GB / 10 -> "${wholeNumber(bytes / (1024.0 * 1024))} MB"
        gb < 10 -> "${oneDecimal(gb)} GB"
        else -> "${wholeNumber(gb)} GB"
    }
}

/**
 * Phase 4 (issue #115) — `:shared`'s Settings screen. Stage E1 (issue #136) built this out
 * from a bare Appearance-display-only placeholder to cover everything whose data layer was
 * already commonMain: real theme/book-layout/cover-tap controls, Downloads, the format-badge
 * legend, and About — see [SettingsState]'s doc comment for exactly what backs each. Native's
 * `feature/settings/SettingsScreen.kt` is not touched by this stage and keeps every feature
 * below plus more.
 *
 * Deliberately still missing, tracked separately (issue #134):
 * - **Book Defaults**: native's audiobook/reader-preference sub-screens read
 *   `PlayerPreferencesStore`/`ReaderPreferencesStore`, neither of which is commonMain yet.
 * - **Reading sync**: per-server KOReader/native sync status needs `KoSyncRepository`
 *   exposed on [AppContainer] (currently private).
 * - **Report a problem**: native's version emails a zip of Android log files
 *   (`CrashReporter`/`DiagnosticsArchive`, both Android-only) — no iOS equivalent has ever
 *   been designed, so this isn't a portability gap so much as undesigned territory.
 *
 * **Servers**: Stage D (issue #133) relocated [net.dexxicon.reader.shared.servers]'s server
 * list here — [onManageServers] just navigates to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer, onManageServers: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { SettingsState(container, scope) }
    val prefs by state.preferences.collectAsState()
    val usedBytes by state.downloadUsedBytes.collectAsState()

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            SettingsSection("Servers") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onManageServers),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Manage servers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Add, edit, remove, or reorder your servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }

            SettingsSection("Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = prefs.theme == theme,
                            onClick = { state.setTheme(theme) },
                            label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }

                LayoutSpacer(Modifier.height(16.dp))
                Text("Book layout", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "The default for the Library and a server's catalog. Each screen keeps " +
                        "its own grid/list toggle; changing this here resets them all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookViewMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.bookViewDefault == mode,
                            onClick = { state.setBookViewDefault(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }

                LayoutSpacer(Modifier.height(16.dp))
                Text("Tapping a cover", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "In the catalog and Library — Home's Continue/On Deck shelves always jump " +
                        "straight into the reader either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverTapAction.entries.forEach { action ->
                        FilterChip(
                            selected = prefs.coverTapAction == action,
                            onClick = { state.setCoverTapAction(action) },
                            label = { Text(action.label) },
                        )
                    }
                }
            }

            SettingsSection("Downloads") {
                SettingRow(
                    title = "Download over Wi-Fi only",
                    subtitle = "Queued downloads wait for an unmetered connection",
                ) {
                    Switch(checked = prefs.downloadsWifiOnly, onCheckedChange = state::setDownloadsWifiOnly)
                }

                LayoutSpacer(Modifier.height(16.dp))
                Text("Storage limit", style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(formatGigabytes(usedBytes)).append(" used")
                        prefs.downloadLimitBytes?.let { append(" of ").append(formatGigabytes(it)) }
                        append(". A download that would go over is skipped.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DOWNLOAD_LIMIT_OPTIONS.forEach { (label, bytes) ->
                        FilterChip(
                            selected = prefs.downloadLimitBytes == bytes,
                            onClick = { state.setDownloadLimit(bytes) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            SettingsSection("Format badges") {
                Text(
                    "The coloured tag on a cover's bottom-right corner shows its file type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormatLegend(Modifier.padding(top = 8.dp))
            }

            SettingsSection("About") {
                SettingRow(title = "Dexxicon Reader", subtitle = "Version ${state.versionName}") {}
                SettingRow(title = "Source code", subtitle = "github.com/dexxfm/dexxicon-reader") {}
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}
