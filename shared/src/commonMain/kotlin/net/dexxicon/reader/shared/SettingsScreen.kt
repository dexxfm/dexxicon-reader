package net.dexxicon.reader.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 4 (issue #115) — `:shared`'s first Settings screen; there was none before this (Phase
 * 2 never ported `feature/settings`). Deliberately narrower than native's `SettingsScreen.kt`:
 *
 * - **Appearance**: display-only — [net.dexxicon.reader.core.designsystem.theme.DexxiconTheme] only
 *   follows the system setting today (see its own doc comment), so there's no manual
 *   light/dark toggle to wire up yet. Showing a segmented control the user could tap but that
 *   silently does nothing would be worse than not having one.
 * - **Servers**: Stage D (issue #133) relocated [net.dexxicon.reader.shared.servers]'s server
 *   list (add/edit/reorder/remove) here — the Library tab is now the merged grid (matching
 *   native's shape), so it no longer has room for server management the way it used to.
 *   [onManageServers] just navigates; the list itself is unchanged, still
 *   [net.dexxicon.reader.shared.servers.ServersState]'s plain up/down reorder, not native's
 *   fuller drag-to-reorder Settings section — full Settings-screen unification stays a
 *   separate future stage.
 * - **Downloads / reading sync / report-a-problem / version**: no `:shared`-side equivalent
 *   of `DownloadRepository`/`KoSyncRepository`/`CrashReporter`, or a cross-platform app-version
 *   source, exists yet (all Android-only so far) — omitted rather than faked. Real parity
 *   work, not a UI-only gap.
 */
@Composable
fun SettingsScreen(onManageServers: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            SettingsSection("Appearance") {
                Text(
                    "Follows your device's system setting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
