package net.dexxicon.reader.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.shared.catalog.BookDetailScreen
import net.dexxicon.reader.shared.catalog.BooksScreen
import net.dexxicon.reader.shared.catalog.BrowseScreen
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.servers.AddServerState
import net.dexxicon.reader.shared.servers.ServersState
import net.dexxicon.reader.shared.servers.SsoState
import net.dexxicon.reader.shared.servers.TestState
import net.dexxicon.reader.shared.sso.SsoWebViewScreen

// Phase 2: the real app shell — a servers list (with a "no servers yet" empty state) behind a
// NavHost, an add-server form (native login, Slice 1 issue #62; SSO WebView, Slice 2 issue
// #70), and read-only catalog browsing (issue #78) — wired to the Phase 1/2 data layer via
// [AppContainer]. Renders identically on Android ([SharedPreviewActivity], debug-only) and
// iOS ([MainViewController]).
@Serializable private object ServersRoute
@Serializable private object AddServerRoute
@Serializable private data class EditServerRoute(val serverId: String)
@Serializable private object BrowseRoute
@Serializable private data class BooksRoute(val serverId: String)
@Serializable private data class BookDetailRoute(val serverId: String, val bookId: String)

@Composable
fun App(container: AppContainer) {
    MaterialTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = ServersRoute) {
            composable<ServersRoute> {
                ServersScreen(
                    container = container,
                    onAddServer = { nav.navigate(AddServerRoute) },
                    onEditServer = { serverId -> nav.navigate(EditServerRoute(serverId)) },
                    onOpenServer = { serverId -> nav.navigate(BooksRoute(serverId)) },
                    onBrowseAll = { nav.navigate(BrowseRoute) },
                )
            }
            composable<AddServerRoute> {
                AddServerScreen(
                    container = container,
                    editingId = null,
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                )
            }
            composable<EditServerRoute> { entry ->
                val route = entry.toRoute<EditServerRoute>()
                AddServerScreen(
                    container = container,
                    editingId = route.serverId,
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                )
            }
            composable<BrowseRoute> {
                BrowseScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { serverId, bookId -> nav.navigate(BookDetailRoute(serverId, bookId)) },
                )
            }
            composable<BooksRoute> { entry ->
                val route = entry.toRoute<BooksRoute>()
                BooksScreen(
                    container = container,
                    serverId = route.serverId,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { bookId -> nav.navigate(BookDetailRoute(route.serverId, bookId)) },
                )
            }
            composable<BookDetailRoute> { entry ->
                val route = entry.toRoute<BookDetailRoute>()
                BookDetailScreen(
                    container = container,
                    serverId = route.serverId,
                    bookId = route.bookId,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreen(
    container: AppContainer,
    onAddServer: () -> Unit,
    onEditServer: (serverId: String) -> Unit,
    onOpenServer: (serverId: String) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val servers by container.serverRepository.servers.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val serversState = remember { ServersState(container.serverRepository, scope) }
    var pendingDelete by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dexxicon") },
                actions = { TextButton(onClick = onBrowseAll) { Text("Browse all") } },
            )
        },
    ) { padding ->
        val list = servers
        when {
            list == null -> Unit // first emission still pending
            list.isEmpty() -> EmptyServersState(Modifier.fillMaxSize().padding(padding), onAddServer)
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(list, key = { _, server -> server.id }) { index, server ->
                    ListItem(
                        headlineContent = { Text(server.displayName) },
                        supportingContent = {
                            Column {
                                Text(server.baseUrl)
                                // issue #92 — "me" endpoint reads back who the app is
                                // actually signed in as; absent until that call resolves
                                // (or for a server type it doesn't apply to).
                                serversState.accounts[server.id]?.let {
                                    Text(
                                        "Signed in as $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                // Plain up/down rather than the native Settings screen's
                                // long-press drag gesture — issue #92 deliberately keeps this
                                // simple (see ServersState's doc comment).
                                TextButton(
                                    onClick = { serversState.moveUp(list, server.id) },
                                    enabled = index > 0,
                                ) { Text("▲") }
                                TextButton(
                                    onClick = { serversState.moveDown(list, server.id) },
                                    enabled = index < list.lastIndex,
                                ) { Text("▼") }
                                // NATIVE opens the field-editing form (#90); OIDC opens the
                                // reauth-only screen instead (#94) — a BASIC server (generic
                                // OPDS) gets neither, :shared has no add-flow for those.
                                when (server.authMode) {
                                    AuthMode.NATIVE -> TextButton(onClick = { onEditServer(server.id) }) { Text("Edit") }
                                    AuthMode.OIDC -> TextButton(onClick = { onEditServer(server.id) }) { Text("Sign in again") }
                                    AuthMode.BASIC -> Unit
                                }
                                TextButton(onClick = { pendingDelete = server }) { Text("Remove") }
                            }
                        },
                        modifier = Modifier.clickable { onOpenServer(server.id) },
                    )
                    HorizontalDivider()
                }
                item {
                    TextButton(
                        onClick = onAddServer,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text("+ Add a server") }
                }
            }
        }
    }

    // A confirmation dialog, not a swipe gesture — issue #72 deliberately keeps this to a
    // tap + confirm so a stray touch (or an unfamiliar swipe direction on a round-cornered
    // list) can never silently drop a configured server.
    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${server.displayName}?") },
            text = { Text("This only removes it from Dexxicon — nothing changes on the server itself.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.serverRepository.delete(server.id) }
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyServersState(modifier: Modifier, onAddServer: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("No servers yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add your BookOrbit or Grimmory library to start browsing and reading.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddServer) { Text("Add a server") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServerScreen(
    container: AppContainer,
    editingId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(editingId) {
        AddServerState(
            container.serverProber,
            container.serverRepository,
            container.oidcAuthenticator,
            scope,
            editingId = editingId,
        )
    }

    // While the browser step is in progress, the SSO WebView replaces the form entirely —
    // once completeSso() moves ssoState past Ready (into Exchanging, on the way to Idle+saved
    // or Error), this falls through to the form below, which is fine: the exchange itself
    // needs no WebView, just a network round-trip.
    val sso = state.ssoState
    if (sso is SsoState.Ready) {
        SsoWebViewScreen(
            handshake = sso.handshake,
            pkce = sso.pkce,
            onCode = { code -> state.completeSso(sso.handshake, code, onSaved) },
            onError = state::onSsoError,
            onCancel = state::onSsoCancelled,
        )
        return
    }

    // While an OIDC edit target is loading, its auth mode isn't known yet — wait rather than
    // flash the native/password form for what's about to turn out to be a reauth screen
    // (issue #94).
    if (state.isEditing && state.editingAuthMode == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit server") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        }
        return
    }

    // Reauth-only for an existing OIDC server (issue #94) — no native/password fields apply,
    // just re-run the SSO handshake against the same server id. See AddServerState's doc
    // comment for why this is manual-only, not the native app's auto-detected-expiry flow.
    if (state.isEditing && state.editingAuthMode == AuthMode.OIDC) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign in again") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${state.displayName} uses single sign-on. Sign in again to refresh its session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = state::discoverSso, enabled = state.baseUrl.isNotBlank()) {
                    Text("Sign in with SSO")
                }
                when (sso) {
                    SsoState.Discovering -> CircularProgressIndicator(Modifier.padding(8.dp))
                    is SsoState.Error -> Text(sso.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit server" else "Add server") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = state::onBaseUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("books.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // SSO sign-in only applies to adding a new server — an existing OIDC server uses
            // the reauth-only branch above instead (issue #94).
            if (!state.isEditing) {
                TextButton(onClick = state::discoverSso, enabled = state.baseUrl.isNotBlank()) {
                    Text("Sign in with SSO instead")
                }
                when (sso) {
                    SsoState.Discovering -> CircularProgressIndicator(Modifier.padding(8.dp))
                    is SsoState.Error -> Text(sso.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = state.username,
                onValueChange = state::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = state::onPasswordChange,
                label = { Text(if (state.isEditing) "Password (leave blank to keep current)" else "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = state::onDisplayNameChange,
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val testState = state.testState
            when (testState) {
                TestState.Idle -> Unit
                TestState.Testing -> CircularProgressIndicator(Modifier.padding(8.dp))
                is TestState.Success -> Text(
                    testState.detail,
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestState.Failure -> Text(
                    testState.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(onClick = state::test, enabled = state.canTest) { Text("Test connection") }
            Button(onClick = { state.save(onSaved) }, enabled = state.canSave) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        }
    }
}
