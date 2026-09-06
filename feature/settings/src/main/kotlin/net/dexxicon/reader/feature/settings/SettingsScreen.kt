package net.dexxicon.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.datastore.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    versionName: String,
    viewModel: SettingsViewModel = hiltViewModel(),
    koSyncViewModel: KoSyncSettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val koSyncRows by koSyncViewModel.rows.collectAsStateWithLifecycle()
    val refreshing by koSyncViewModel.refreshing.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = koSyncViewModel::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            SectionTitle("Appearance")
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = prefs.theme == theme,
                        onClick = { viewModel.setTheme(theme) },
                        label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer()
            SectionTitle("Downloads")
            SettingRow(
                title = "Download over Wi-Fi only",
                subtitle = "Queued downloads wait for an unmetered connection",
            ) {
                Switch(
                    checked = prefs.downloadsWifiOnly,
                    onCheckedChange = viewModel::setDownloadsWifiOnly,
                )
            }

            Spacer()
            SectionTitle("Reading sync")
            Text(
                "Reading & listening position syncs with each server. BookOrbit and Grimmory " +
                    "sync through their own library API (same as the web reader); other OPDS " +
                    "servers use a KOReader sync account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (koSyncRows.isEmpty()) {
                Text(
                    "Add a server first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            koSyncRows.forEach { row ->
                if (row.usesNative) {
                    NativeSyncRow(row)
                } else {
                    KoSyncServerCard(
                        row = row,
                        onSave = { url, user, pass -> koSyncViewModel.save(row.serverId, url, user, pass) },
                        onVerify = { koSyncViewModel.verify(row.serverId) },
                    )
                }
                LayoutSpacer(Modifier.height(10.dp))
            }

            Spacer()
            SectionTitle("About")
            SettingRow(title = "Dexxicon Reader", subtitle = "Version $versionName") {}
            SettingRow(
                title = "Source code",
                subtitle = "github.com/dexxfm/dexxicon-reader",
            ) {}
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

private fun relativeTime(atMillis: Long): String {
    val now = System.currentTimeMillis()
    if (atMillis <= 0L || atMillis > now) return "just now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        atMillis,
        now,
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString().replaceFirstChar { it.lowercase() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Spacer() {
    HorizontalDivider(Modifier.padding(vertical = 20.dp))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}
