package net.dexxicon.reader.feature.servers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.feature.servers.sso.OIDC_REDIRECT_URI
import net.dexxicon.reader.feature.servers.sso.OidcAuthFlow
import net.dexxicon.reader.feature.servers.sso.OidcAuthResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerScreen(
    serverId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddEditServerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val authFlow = remember { OidcAuthFlow(context) }
    DisposableEffect(Unit) { onDispose { authFlow.dispose() } }

    val ssoReady = state.sso as? SsoState.Ready

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val handshake = ssoReady?.handshake
        when (val parsed = authFlow.parseResult(result.data)) {
            is OidcAuthResult.Code -> if (handshake != null) {
                viewModel.completeSso(
                    handshake = handshake,
                    redirectUri = OIDC_REDIRECT_URI,
                    code = parsed.code,
                    codeVerifier = parsed.codeVerifier,
                    onSaved = onDone,
                )
            }
            is OidcAuthResult.Failed -> viewModel.onAuthorizeFailed(parsed.message)
            OidcAuthResult.Cancelled -> viewModel.onAuthorizeCancelled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (serverId == null) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://books.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SsoBlock(
                state = state.sso,
                onDiscover = viewModel::discoverSso,
                onSignIn = {
                    ssoReady?.let {
                        viewModel.onAuthorizing()
                        authLauncher.launch(authFlow.authorizationIntent(it.handshake))
                    }
                },
                onCancel = viewModel::onAuthorizeCancelled,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "  or use a password  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(if (serverId != null) "Password (leave blank to keep)" else "Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide" else "Show",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            TestConnectionRow(
                state = state.testState,
                enabled = state.canTest && state.testState !is TestState.Testing,
                onTest = viewModel::test,
            )

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { viewModel.save(onDone) },
                enabled = state.canSave && !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (serverId == null) "Add server" else "Save changes")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Credentials and tokens are stored encrypted on this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SsoBlock(
    state: SsoState,
    onDiscover: () -> Unit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        when (state) {
            is SsoState.Ready -> {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Text("  Continue with ${state.handshake.providerName ?: "SSO"}")
                }
            }
            SsoState.Discovering, SsoState.Exchanging -> {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (state == SsoState.Discovering) "  Looking for SSO…"
                        else "  Finishing sign-in…",
                    )
                }
            }
            SsoState.Authorizing -> {
                // Tappable so the user can back out if they dismiss the browser tab.
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Waiting for browser — tap to cancel")
                }
            }
            else -> {
                OutlinedButton(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Text("  Sign in with SSO")
                }
            }
        }
        if (state is SsoState.Error) {
            Spacer(Modifier.height(8.dp))
            StatusLine(Icons.Filled.Error, MaterialTheme.colorScheme.error, state.message)
        }
    }
}

@Composable
private fun TestConnectionRow(
    state: TestState,
    enabled: Boolean,
    onTest: () -> Unit,
) {
    Column {
        OutlinedButton(onClick = onTest, enabled = enabled) {
            if (state is TestState.Testing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Testing…")
            } else {
                Text("Test connection")
            }
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            is TestState.Success ->
                StatusLine(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, state.detail)
            is TestState.Failure ->
                StatusLine(Icons.Filled.Error, MaterialTheme.colorScheme.error, state.message)
            else -> Unit
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Text("  $text", style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
