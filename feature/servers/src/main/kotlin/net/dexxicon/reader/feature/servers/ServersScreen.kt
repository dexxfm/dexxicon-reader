package net.dexxicon.reader.feature.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.shared.ServersScreen as SharedServersScreen
import net.dexxicon.reader.shared.di.AndroidAppContainer

/**
 * Phase 4 Stage G (issue #144) — a thin platform entry point, same shape as
 * `feature/catalog/BookDetailScreen.kt`'s own doc comment: `:shared`'s `ServersScreen` (drag-
 * reorder, edit/remove menu, SSO-aware) holds the real logic and render. This is what used to
 * be the server list embedded inside native's own `SettingsScreen.kt` via `ServerListViewModel`
 * — now its own route, reached from Settings' "Manage servers" row, matching how `:shared`'s
 * own Settings has worked since Stage D.
 */
@Composable
fun ServersScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (serverId: String) -> Unit,
    onOpenServer: (serverId: String) -> Unit,
) {
    val context = LocalContext.current
    val container = remember { AndroidAppContainer.get(context) }
    SharedServersScreen(
        container = container,
        onBack = onBack,
        onAddServer = onAddServer,
        onEditServer = onEditServer,
        onOpenServer = onOpenServer,
    )
}
