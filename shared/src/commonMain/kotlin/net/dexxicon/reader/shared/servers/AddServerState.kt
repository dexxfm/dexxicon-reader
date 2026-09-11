package net.dexxicon.reader.shared.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.OidcAuthenticator
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerProbeResult
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake
import net.dexxicon.reader.shared.sso.Pkce

/**
 * The add-server form's state + behaviour — the shared-UI equivalent of the native
 * `AddEditServerViewModel` (`feature/servers`), scoped down to what's actually needed here:
 * adding rather than editing, and (issue #70) only the `WEBVIEW` SSO flow — both server
 * families' handshakes always resolve to it, `CUSTOM_SCHEME`/AppAuth is never actually
 * produced (see [OidcAuthenticator]'s callers). There's no `ViewModel`/Hilt here (see
 * [net.dexxicon.reader.shared.di.AppContainer]'s doc comment) — a plain class holding the
 * [scope] the caller got from `rememberCoroutineScope()` plays the same role.
 */
class AddServerState(
    private val serverProber: ServerProber,
    private val serverRepository: ServerRepository,
    private val oidcAuthenticator: OidcAuthenticator,
    private val scope: CoroutineScope,
) {
    var displayName by mutableStateOf("")
        private set
    var baseUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var testState: TestState by mutableStateOf(TestState.Idle)
        private set
    var saving by mutableStateOf(false)
        private set
    var ssoState: SsoState by mutableStateOf(SsoState.Idle)
        private set

    private var pendingPkce: Pkce? = null

    /** Adding a server requires a successful connection test first — same rule as the native
     * form, so a server never gets saved with a type/auth-mode nobody actually verified. */
    val canTest: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
            testState != TestState.Testing
    val canSave: Boolean
        get() = displayName.isNotBlank() && baseUrl.isNotBlank() &&
            testState is TestState.Success && !saving

    fun onDisplayNameChange(value: String) { displayName = value }
    fun onBaseUrlChange(value: String) {
        baseUrl = value
        testState = TestState.Idle
        ssoState = SsoState.Idle
    }
    fun onUsernameChange(value: String) { username = value; testState = TestState.Idle }
    fun onPasswordChange(value: String) { password = value; testState = TestState.Idle }

    fun test() {
        if (!canTest) return
        testState = TestState.Testing
        scope.launch {
            testState = when (val result = serverProber.probe(baseUrl, username, password)) {
                is ServerProbeResult.Success -> {
                    if (displayName.isBlank()) displayName = prettyHost(baseUrl)
                    TestState.Success(result.detectedType, connectedLabel(result.detectedType))
                }
                is ServerProbeResult.InvalidCredentials -> TestState.Failure(result.message)
                is ServerProbeResult.Unreachable -> TestState.Failure(result.message)
                is ServerProbeResult.NotAServer -> TestState.Failure(result.message)
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val success = testState as? TestState.Success ?: return
        if (!canSave) return
        saving = true
        scope.launch {
            serverRepository.save(
                server = Server(
                    id = "",
                    displayName = displayName.trim(),
                    baseUrl = normalizeUrl(baseUrl),
                    type = success.type,
                    authMode = AuthMode.NATIVE,
                    username = username.trim(),
                ),
                password = password,
            )
            saving = false
            onSaved()
        }
    }

    /** Step 1 of SSO: discover the provider + fetch a fresh `state`/PKCE pair. */
    fun discoverSso() {
        if (baseUrl.isBlank() || ssoState == SsoState.Discovering) return
        ssoState = SsoState.Discovering
        scope.launch {
            when (val result = oidcAuthenticator.beginHandshake(ssoCandidateServer())) {
                is Outcome.Success -> {
                    val pkce = Pkce.generate()
                    pendingPkce = pkce
                    ssoState = SsoState.Ready(result.value, pkce)
                }
                is Outcome.Failure -> ssoState =
                    SsoState.Error(result.error.message ?: "SSO is not available")
            }
        }
    }

    /** Step 2: [SsoWebViewScreen][net.dexxicon.reader.shared.sso.SsoWebViewScreen] captured an
     * authorization code — exchange it and persist the server. */
    fun completeSso(handshake: OidcHandshake, code: String, onSaved: () -> Unit) {
        val pkce = pendingPkce ?: run {
            ssoState = SsoState.Error("Sign-in state was lost — try again")
            return
        }
        ssoState = SsoState.Exchanging
        scope.launch {
            when (
                val result = oidcAuthenticator.completeAndSave(
                    pendingServer = ssoCandidateServer(),
                    handshake = handshake,
                    code = code,
                    codeVerifier = pkce.verifier,
                    nonce = pkce.nonce,
                )
            ) {
                is Outcome.Success -> {
                    pendingPkce = null
                    ssoState = SsoState.Idle
                    onSaved()
                }
                is Outcome.Failure -> ssoState =
                    SsoState.Error(result.error.message ?: "Sign-in failed")
            }
        }
    }

    fun onSsoError(message: String) { ssoState = SsoState.Error(message) }
    fun onSsoCancelled() { ssoState = SsoState.Idle }

    private fun ssoCandidateServer(): Server = Server(
        id = "",
        displayName = displayName.trim().ifBlank { prettyHost(baseUrl) },
        baseUrl = normalizeUrl(baseUrl),
        authMode = AuthMode.OIDC,
    )

    private fun connectedLabel(type: ServerType): String =
        type.name.lowercase().replaceFirstChar(Char::uppercase) + " · connected"

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun prettyHost(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')
}

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val type: ServerType, val detail: String) : TestState
    data class Failure(val message: String) : TestState
}

sealed interface SsoState {
    data object Idle : SsoState
    data object Discovering : SsoState
    data class Ready(val handshake: OidcHandshake, val pkce: Pkce) : SsoState
    data object Exchanging : SsoState
    data class Error(val message: String) : SsoState
}
