package net.dexxicon.reader.feature.servers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.shared.AddServerScreen
import net.dexxicon.reader.shared.di.AndroidAppContainer

/**
 * Phase 4 Stage G (issue #144) — a thin platform entry point, same shape as
 * `feature/catalog/BookDetailScreen.kt`'s own doc comment: `:shared`'s `AddServerScreen`/
 * `AddServerState` (full form, optional SSO section, KOReader sync sheet — ported to match
 * this screen exactly) hold the real logic and render. `AddEditServerViewModel` and the
 * `sso/` subpackage (custom-scheme AppAuth flow, this screen's own WebView) are gone —
 * `:shared` already has a proven, working WebView-based SSO flow of its own.
 *
 * [reauth] is native's session-expiry auto-reauth entry point (`ReauthCoordinator`/
 * `SignInNotifier`, both still native-only — see [net.dexxicon.reader.shared.servers.AddServerState]'s
 * doc comment for why): it's threaded straight through so `discoverSso()` still kicks off
 * immediately, matching this screen's previous `AddEditServerRoute.reauth` behavior exactly.
 */
@Composable
fun AddEditServerScreen(
    serverId: String?,
    reauth: Boolean = false,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { AndroidAppContainer.get(context) }
    AddServerScreen(
        container = container,
        editingId = serverId,
        onBack = onBack,
        onSaved = onDone,
        reauth = reauth,
    )
}
