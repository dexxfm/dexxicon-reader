package net.dexxicon.reader.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 4 (issue #115) — `:shared`'s first Settings screen; there was none before this (Phase
 * 2 never ported `feature/settings`). Deliberately narrower than native's `SettingsScreen.kt`:
 *
 * - **Appearance**: display-only — [net.dexxicon.reader.shared.theme.DexxiconTheme] only
 *   follows the system setting today (see its own doc comment), so there's no manual
 *   light/dark toggle to wire up yet. Showing a segmented control the user could tap but that
 *   silently does nothing would be worse than not having one.
 * - **Servers**: deliberately left off this screen — [net.dexxicon.reader.shared.servers]'s
 *   server list (add/edit/reorder/remove) already lives behind the Library tab in
 *   [net.dexxicon.reader.shared.App], reusing what's already there rather than duplicating the
 *   same [net.dexxicon.reader.shared.servers.ServersState] under two tabs.
 * - **Downloads / reading sync / report-a-problem / version**: no `:shared`-side equivalent
 *   of `DownloadRepository`/`KoSyncRepository`/`CrashReporter`, or a cross-platform app-version
 *   source, exists yet (all Android-only so far) — omitted rather than faked. Real parity
 *   work, not a UI-only gap.
 */
@Composable
fun SettingsScreen() {
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

            Text(
                "Server setup lives under Library — pick a server there to browse it, or use " +
                    "its Edit / Remove controls to manage it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
