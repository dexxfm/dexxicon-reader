package net.dexxicon.reader.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.servers.AddServerState
import net.dexxicon.reader.shared.servers.SsoState
import net.dexxicon.reader.shared.servers.TestState
import net.dexxicon.reader.shared.sso.SsoWebViewScreen

// Phase 2: the real app shell — a servers list (with a "no servers yet" empty state) behind a
// NavHost, and an add-server form (native login, Slice 1 issue #62; SSO WebView, Slice 2
// issue #70) wired to the Phase 1 data layer via [AppContainer]. Renders identically on
// Android ([SharedPreviewActivity], debug-only) and iOS ([MainViewController]).
@Serializable private object ServersRoute
@Serializable private object AddServerRoute

@Composable
fun App(container: AppContainer) {
    MaterialTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = ServersRoute) {
            composable<ServersRoute> {
                ServersScreen(
                    container = container,
                    onAddServer = { nav.navigate(AddServerRoute) },
                )
            }
            composable<AddServerRoute> {
                AddServerScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreen(container: AppContainer, onAddServer: () -> Unit) {
    val servers by container.serverRepository.servers.collectAsState(initial = null)

    Scaffold(topBar = { TopAppBar(title = { Text("Dexxicon") }) }) { padding ->
        val list = servers
        when {
            list == null -> Unit // first emission still pending
            list.isEmpty() -> EmptyServersState(Modifier.fillMaxSize().padding(padding), onAddServer)
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list) { server ->
                    ListItem(
                        headlineContent = { Text(server.displayName) },
                        supportingContent = { Text(server.baseUrl) },
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
private fun AddServerScreen(container: AppContainer, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember {
        AddServerState(container.serverProber, container.serverRepository, container.oidcAuthenticator, scope)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add server") },
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

            TextButton(onClick = state::discoverSso, enabled = state.baseUrl.isNotBlank()) {
                Text("Sign in with SSO instead")
            }
            when (sso) {
                SsoState.Discovering -> CircularProgressIndicator(Modifier.padding(8.dp))
                is SsoState.Error -> Text(sso.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
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
                label = { Text("Password") },
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
