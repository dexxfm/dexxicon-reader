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

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
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
            SectionTitle("Reading sync (koreader)")
            Text(
                "Share reading progress with the KOReader app and other devices. Each server " +
                    "needs a dedicated sync account (KOReader plugin).",
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
                KoSyncServerCard(
                    row = row,
                    onSave = { url, user, pass -> koSyncViewModel.save(row.serverId, url, user, pass) },
                    onVerify = { koSyncViewModel.verify(row.serverId) },
                )
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

@Composable
private fun KoSyncServerCard(
    row: KoSyncServerRow,
    onSave: (url: String, user: String, pass: String) -> Unit,
    onVerify: () -> Unit,
) {
    var url by remember(row.serverId) { mutableStateOf(row.koSyncUrl) }
    var user by remember(row.serverId) { mutableStateOf(row.koSyncUsername) }
    var pass by remember(row.serverId) { mutableStateOf("") }
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
                }
                if (row.verifying) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Close" else "Edit")
                }
            }
            if (expanded) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Sync server URL") },
                    placeholder = { Text("https://host/koreader") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
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
                    TextButton(onClick = { onSave(url, user, pass); expanded = false }) { Text("Save") }
                }
            }
        }
    }
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
