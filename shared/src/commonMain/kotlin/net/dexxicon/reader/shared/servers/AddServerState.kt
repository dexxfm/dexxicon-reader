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
 * The add/edit-server form's state + behaviour — the shared-UI equivalent of the native
 * `AddEditServerViewModel` (`feature/servers`), scoped down to what's actually needed here:
 * (issue #70) only the `WEBVIEW` SSO flow — both server families' handshakes always resolve
 * to it, `CUSTOM_SCHEME`/AppAuth is never actually produced (see [OidcAuthenticator]'s
 * callers). There's no `ViewModel`/Hilt here (see [net.dexxicon.reader.shared.di.AppContainer]'s
 * doc comment) — a plain class holding the [scope] the caller got from
 * `rememberCoroutineScope()` plays the same role.
 *
 * [editingId] (issue #90), when non-null, switches this from "add" to "edit": the constructor
 * loads the existing [Server] from [serverRepository] and pre-fills the form, [canSave] no
 * longer requires a fresh [test] pass first (the user may just be fixing a display name), and
 * [save] keeps the server's existing id/type/auth-mode rather than minting a new one. A blank
 * [password] on save means "keep the stored one" — [ServerRepository.save]'s `password` param
 * is already nullable for exactly this.
 *
 * Phase 4 Stage G (issue #144): the form itself is always the same regardless of the loaded
 * server's auth mode — matching native's `AddEditServerScreen` exactly — with "Sign in with
 * SSO" as one more optional action on the form (via [ssoState]/[discoverSso]), not a
 * different screen. An earlier version of this class swapped to a reauth-only UI for OIDC
 * servers (issue #94); that traded away the ability to rename/re-point an SSO server at all,
 * which native never did, so it's gone. [reauth], when the caller is landing here from
 * native's session-expiry notification/banner (`ReauthCoordinator`/`SignInNotifier` —
 * necessarily Android-only, `MainActivity`'s launch-intent extra and a system notification
 * have no portable equivalent, so that detection stays in `:app`), immediately kicks off
 * [discoverSso] instead of waiting for the user to tap the button — same as native's
 * `AddEditServerRoute.reauth`/`AddEditServerViewModel.init`.
 */
class AddServerState(
    private val serverProber: ServerProber,
    private val serverRepository: ServerRepository,
    private val oidcAuthenticator: OidcAuthenticator,
    private val scope: CoroutineScope,
    private val editingId: String? = null,
    private val reauth: Boolean = false,
) {
    var displayName by mutableStateOf("")
        private set
    var baseUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var koSyncUrl by mutableStateOf("")
        private set
    var koSyncUsername by mutableStateOf("")
        private set
    var koSyncPassword by mutableStateOf("")
        private set
    var testState: TestState by mutableStateOf(TestState.Idle)
        private set
    var saving by mutableStateOf(false)
        private set
    var ssoState: SsoState by mutableStateOf(SsoState.Idle)
        private set

    private var pendingPkce: Pkce? = null

    /** Set once the edit target loads; null while loading and for a plain "add" form. */
    private var loadedServer: Server? by mutableStateOf(null)
        private set

    val isEditing: Boolean get() = editingId != null

    init {
        if (editingId != null) {
            scope.launch {
                serverRepository.get(editingId)?.let { server ->
                    loadedServer = server
                    displayName = server.displayName
                    baseUrl = server.baseUrl
                    username = server.username
                    koSyncUrl = server.koSyncUrl.orEmpty()
                    koSyncUsername = server.koSyncUsername.orEmpty()
                    if (reauth) discoverSso()
                }
            }
        }
    }

    /** Adding a server requires a successful connection test first — same rule as the native
     * form, so a server never gets saved with a type/auth-mode nobody actually verified.
     * Editing doesn't — the user may not be touching the URL/credentials at all. */
    val canTest: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
            testState != TestState.Testing
    val canSave: Boolean
        get() = displayName.isNotBlank() && baseUrl.isNotBlank() &&
            (isEditing || testState is TestState.Success) && !saving

    fun onDisplayNameChange(value: String) { displayName = value }
    fun onBaseUrlChange(value: String) {
        baseUrl = value
        testState = TestState.Idle
        ssoState = SsoState.Idle
    }
    fun onUsernameChange(value: String) { username = value; testState = TestState.Idle }
    fun onPasswordChange(value: String) { password = value; testState = TestState.Idle }
    fun onKoSyncUrlChange(value: String) { koSyncUrl = value }
    fun onKoSyncUsernameChange(value: String) { koSyncUsername = value }
    fun onKoSyncPasswordChange(value: String) { koSyncPassword = value }

    fun test() {
        if (!canTest) return
        testState = TestState.Testing
        scope.launch {
            // issue #177: matches save()'s existing `username.trim()` — without it, stray
            // leading/trailing whitespace a keyboard's predictive-text bar can silently insert
            // (confirmed live on iOS: the submitted username failed a round-trip trim check)
            // makes the connection test fail even though the exact same credentials work
            // everywhere else, since save() would have trimmed it but test() never got that far.
            testState = when (val result = serverProber.probe(baseUrl, username.trim(), password)) {
                is ServerProbeResult.Success -> {
                    if (displayName.isBlank()) displayName = prettyHost(result.baseUrl)
                    // issue #267 — show the address that actually worked (the scheme may have
                    // been filled in), so what's saved is what the user sees. Set directly
                    // rather than via onBaseUrlChange, which would reset this very result.
                    baseUrl = result.baseUrl
                    TestState.Success(result.detectedType, connectedLabel(result.detectedType, result.baseUrl), result.baseUrl)
                }
                is ServerProbeResult.InvalidCredentials -> TestState.Failure(result.message)
                is ServerProbeResult.Unreachable -> TestState.Failure(result.message)
                is ServerProbeResult.NotAServer -> TestState.Failure(result.message)
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        if (!canSave) return
        val success = testState as? TestState.Success
        val base = loadedServer
        saving = true
        scope.launch {
            serverRepository.save(
                // issue #96: copy from the loaded server, not a bare Server(...) — editing
                // only ever changes the fields this form surfaces; building from scratch
                // silently reset sortOrder/createdAt/koSync* to their defaults on every save.
                server = (base ?: Server(id = "", displayName = "", baseUrl = "")).copy(
                    id = editingId ?: "",
                    displayName = displayName.trim(),
                    baseUrl = success?.baseUrl ?: normalizeUrl(baseUrl),
                    type = success?.type ?: base?.type ?: ServerType.GENERIC,
                    // Keep an existing server's auth mode — editing a field shouldn't
                    // silently downgrade an SSO server to password auth. An OIDC server can
                    // still edit its name/URL/username here; re-authenticating is a separate
                    // action (discoverSso/completeSso) matching native's form exactly.
                    authMode = base?.authMode ?: AuthMode.NATIVE,
                    username = username.trim(),
                    koSyncUrl = koSyncUrl.trim().trimEnd('/').takeIf { it.isNotBlank() },
                    koSyncUsername = koSyncUsername.trim().takeIf { it.isNotBlank() },
                ),
                // Blank means "keep the stored password" when editing — never overwrite a
                // real secret with an empty one just because the field was left untouched.
                password = password.takeIf { it.isNotBlank() },
                koSyncPassword = koSyncPassword.takeIf { it.isNotBlank() },
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

    private fun ssoCandidateServer(): Server {
        // issue #96: copy from the loaded server (reauth) rather than building a bare
        // Server(...) — same fix as save(), same reason: sortOrder/createdAt/koSync* would
        // otherwise silently reset to their defaults on every reauth.
        val base = loadedServer
        return (base ?: Server(id = "", displayName = "", baseUrl = "")).copy(
            // Reauth (issue #94) keeps the existing id so the save updates that row instead
            // of inserting a new one; a plain add still mints a fresh one via
            // ServerRepository.save.
            id = editingId ?: "",
            displayName = displayName.trim().ifBlank { prettyHost(baseUrl) },
            baseUrl = (testState as? TestState.Success)?.baseUrl ?: normalizeUrl(baseUrl),
            authMode = AuthMode.OIDC,
        )
    }

    /** "Bookorbit · connected", plus a nudge when that connection is plain HTTP (issue #267)
     *  — worth knowing on a LAN, worth fixing if the server is reachable from the internet. */
    private fun connectedLabel(type: ServerType, url: String): String =
        type.name.lowercase().replaceFirstChar(Char::uppercase) + " · connected" +
            if (url.startsWith("http://", ignoreCase = true)) " (not encrypted — HTTP)" else ""

    /** Only for a URL that was never tested (editing a saved server): a missing scheme
     *  defaults to https, same as before issue #267. A tested URL uses [TestState.Success.baseUrl]. */
    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
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
    data class Success(val type: ServerType, val detail: String, val baseUrl: String) : TestState
    data class Failure(val message: String) : TestState
}

sealed interface SsoState {
    data object Idle : SsoState
    data object Discovering : SsoState
    data class Ready(val handshake: OidcHandshake, val pkce: Pkce) : SsoState
    data object Exchanging : SsoState
    data class Error(val message: String) : SsoState
}
