package net.dexxicon.reader.feature.servers.sso

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake

/**
 * WebView-based auth-code flow for servers (BookOrbit) that only allow an https redirect
 * URI. We load the provider's authorize page and intercept the redirect to
 * [OidcHandshake.redirectUri], pulling `code` + `state` off it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OidcWebViewScreen(
    handshake: OidcHandshake,
    pkce: Pkce,
    onCode: (code: String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var progress by remember { mutableStateOf(0) }

    val authUrl = remember(handshake, pkce) {
        Uri.parse(handshake.authorizationEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", handshake.clientId)
            .appendQueryParameter("redirect_uri", handshake.redirectUri)
            .appendQueryParameter("scope", handshake.scopes)
            .appendQueryParameter("state", handshake.state)
            .appendQueryParameter("nonce", pkce.nonce)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
            .also { android.util.Log.i("DexxiconOidc", "webview authorize: $it") }
    }

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in with ${handshake.providerName ?: "SSO"}") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
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
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = CallbackInterceptingClient(
                            redirectPrefix = handshake.redirectUri,
                            expectedState = handshake.state,
                            onCode = onCode,
                            onError = onError,
                            onProgress = { progress = it },
                        )
                        loadUrl(authUrl)
                    }
                },
            )
        }
    }
}

private class CallbackInterceptingClient(
    private val redirectPrefix: String,
    private val expectedState: String,
    private val onCode: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
) : WebViewClient() {

    private var finished = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return maybeIntercept(request.url)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onProgress(10)
        url?.let { if (maybeIntercept(Uri.parse(it))) view?.stopLoading() }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onProgress(100)
    }

    private fun maybeIntercept(uri: Uri): Boolean {
        if (finished) return true
        if (!uri.toString().startsWith(redirectPrefix)) return false
        finished = true

        val error = uri.getQueryParameter("error")
        if (error != null) {
            onError(uri.getQueryParameter("error_description") ?: error)
            return true
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        when {
            code == null -> onError("No authorization code was returned")
            state != expectedState -> onError("Security check failed — try again")
            else -> onCode(code)
        }
        return true
    }
}
