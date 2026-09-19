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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.round
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.designsystem.component.FormatLegend
import net.dexxicon.reader.core.designsystem.nav.FloatingNavClearance
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.GlassIntensity
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.home.relativeTime

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
 * Phase 4 (issue #115) — `:shared`'s Settings screen. Stage E1 (issue #136) built out
 * everything whose data layer was already commonMain: real theme/book-layout/cover-tap
 * controls, Downloads, the format-badge legend, and About. Stage H (issue #145) filled in
 * the rest once its data layer went commonMain too: Book Defaults ([onOpenAudiobookDefaults]/
 * [onOpenBookDefaults] navigate to [AudiobookDefaultsScreen]/[BookDefaultsScreen]) and
 * Reading sync (pull-to-refresh here reconciles progress and re-verifies every KOReader
 * account, same as native's version) — see [SettingsState]'s doc comment for exactly what
 * backs each.
 *
 * [onReportProblem] emails a zip of Android-only log files (`CrashReporter`/
 * `DiagnosticsArchive`); no iOS equivalent has ever been designed. It's an optional platform
 * callback — Android's thin wrapper (`DexxiconApp.kt`, wired through [App]'s own
 * `onReportProblem` param, issue #262) passes a real implementation reusing that existing
 * Intent+zip logic (the same shape [net.dexxicon.reader.shared.OnOpenReader] already uses for
 * a platform capability `:shared` doesn't implement itself); leaving it null (iOS, for now)
 * simply omits the section rather than showing a broken one.
 *
 * **Servers**: Stage D (issue #133) relocated [net.dexxicon.reader.shared.servers]'s server
 * list here — [onManageServers] just navigates to it.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onManageServers: () -> Unit,
    onOpenAudiobookDefaults: () -> Unit,
    onOpenBookDefaults: () -> Unit,
    onReportProblem: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val state = remember { SettingsState(container, scope) }
    val prefs by state.preferences.collectAsState()
    val usedBytes by state.downloadUsedBytes.collectAsState()
    val syncRows by state.syncRows.collectAsState()
    val syncRefreshing by state.syncRefreshing.collectAsState()

    Scaffold { padding ->
        PullToRefreshBox(
            isRefreshing = syncRefreshing,
            onRefresh = state::refreshAllSync,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // issue #132 — bottom clears the floating nav, which now overlays content
                // instead of reserving space for it.
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = FloatingNavClearance),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            SettingsSection("Servers") {
                NavRow(
                    title = "Manage servers",
                    subtitle = "Add, edit, remove, or reorder your servers.",
                    onClick = onManageServers,
                )
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
                Text("Default sort", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "The order new lists start in for the Library and a server's catalog. Each " +
                        "screen keeps its own sort; changing this here resets them all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookSort.entries.forEach { sort ->
                        FilterChip(
                            selected = prefs.bookSortDefault == sort,
                            onClick = { state.setBookSortDefault(sort) },
                            label = { Text(sort.name.lowercase().replaceFirstChar { it.uppercase() }) },
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

                LayoutSpacer(Modifier.height(16.dp))
                Text("Glass intensity", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "How strong the floating nav bar's blurred-glass look is. Off keeps the " +
                        "flat tint from before and never blurs anything, for low-end devices or " +
                        "if you just prefer it flat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIntensity.entries.forEach { intensity ->
                        FilterChip(
                            selected = prefs.glassIntensity == intensity,
                            onClick = { state.setGlassIntensity(intensity) },
                            label = { Text(intensity.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            SettingsSection("Book Defaults") {
                Text(
                    "The look, page-turn feel and playback options each format opens with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NavRow(
                    title = "Audiobooks",
                    subtitle = "Default speed, skip silence",
                    onClick = onOpenAudiobookDefaults,
                )
                NavRow(
                    title = "Books",
                    subtitle = "EPUB, comics and PDF — text size, background, page-turn swipe",
                    onClick = onOpenBookDefaults,
                )
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

            SettingsSection("Reading sync") {
                Text(
                    "Reading & listening position syncs with each server. BookOrbit and Grimmory " +
                        "sync through their own library API (same as the web reader); other OPDS " +
                        "servers use a KOReader sync account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (syncRows.isEmpty()) {
                    Text(
                        "Add a server first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                syncRows.forEach { row ->
                    if (row.usesNative) {
                        NativeSyncRow(row)
                    } else {
                        KoSyncServerCard(
                            row = row,
                            onSave = { url, user, pass -> state.saveSync(row.serverId, url, user, pass) },
                            onVerify = { state.verifySync(row.serverId) },
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

            if (onReportProblem != null) {
                SettingsSection("Feedback") {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onReportProblem),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Report a problem", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Opens an email with your device details and a zip of the app's logs " +
                                    "attached. Nothing is sent until you send it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SettingsSection("About") {
                SettingRow(title = "Dexxicon Reader", subtitle = "Version ${state.versionName}") {}
                SettingRow(title = "Source code", subtitle = "github.com/dexxfm/dexxicon-reader") {}
            }
        }
        }
    }
}

@Composable
private fun NativeSyncRow(row: SyncServerRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Syncs with the library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.lastSyncedAt?.let { at ->
                Text(
                    "Last synced ${relativeTime(at)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KoSyncServerCard(
    row: SyncServerRow,
    onSave: (customUrl: String, user: String, pass: String) -> Unit,
    onVerify: () -> Unit,
) {
    var user by remember(row.serverId) { mutableStateOf(row.koSyncUsername) }
    var pass by remember(row.serverId) { mutableStateOf("") }
    var customUrl by remember(row.serverId) { mutableStateOf(row.customUrl) }
    var useCustom by remember(row.serverId) { mutableStateOf(row.customUrl.isNotBlank()) }
    var expanded by remember(row.serverId) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                    val status = when {
                        row.verifying -> "Checking…"
                        row.verified == true -> "(koreader) · connected"
                        row.verified == false -> "(koreader) · sign-in failed"
                        row.configured -> "(koreader) · configured"
                        else -> "Not set up"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.verified == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    row.lastSyncedAt?.let { at ->
                        Text(
                            "Last synced ${relativeTime(at)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (row.verifying) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Close" else "Edit")
                }
            }
            if (expanded) {
                Text(
                    "Sync endpoint",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useCustom,
                        onClick = { useCustom = false },
                        label = { Text("Assumed") },
                    )
                    FilterChip(
                        selected = useCustom,
                        onClick = { useCustom = true },
                        label = { Text("Custom") },
                    )
                }
                if (!useCustom) {
                    Text(
                        row.assumedUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Custom sync URL") },
                        placeholder = { Text(row.assumedUrl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Sync username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(if (row.configured) "Sync password (leave blank to keep)" else "Sync password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onVerify, enabled = row.configured) { Text("Verify") }
                    TextButton(onClick = {
                        onSave(if (useCustom) customUrl else "", user, pass)
                        expanded = false
                    }) { Text("Save") }
                }
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

/** A Settings row that navigates elsewhere — the Servers and Book Defaults entries. */
@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun SettingRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
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
