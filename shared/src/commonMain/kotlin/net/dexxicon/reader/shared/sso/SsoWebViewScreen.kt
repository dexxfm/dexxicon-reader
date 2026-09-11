package net.dexxicon.reader.shared.sso

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake

/**
 * The SSO WebView screen: loads [handshake]'s authorize page and reports back the outcome —
 * a code to exchange, or an error — once [SsoWebView] reports a redirect. Shared-UI
 * equivalent of the native `feature/servers` `OidcWebViewScreen`, minus the platform WebView
 * itself (behind [SsoWebView], see its doc comment).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SsoWebViewScreen(
    handshake: OidcHandshake,
    pkce: Pkce,
    onCode: (code: String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val authUrl = remember(handshake, pkce) { buildAuthorizeUrl(handshake, pkce) }
    var progress by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in with ${handshake.providerName ?: "SSO"}") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("‹ Cancel") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SsoWebView(
                url = authUrl,
                redirectPrefix = handshake.redirectUri,
                modifier = Modifier.fillMaxSize(),
                onProgress = { progress = it },
                onRedirect = { capturedUrl ->
                    when (val result = readOidcRedirect(capturedUrl, handshake)) {
                        is OidcRedirectResult.Code -> onCode(result.code)
                        is OidcRedirectResult.Error -> onError(result.message)
                    }
                },
            )
        }
    }
}
