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
 * When [editingId] targets an [AuthMode.OIDC] server ([editingAuthMode]), the caller switches
 * to a reauth-only UI (issue #94) instead of the native/password form — [ssoCandidateServer]
 * carries [editingId] as its id (rather than `""`), so [completeSso] updates that same row via
 * [ServerRepository.save]'s existing upsert-by-id behavior instead of inserting a new server.
 * Deliberately **manual reauth only**: no automatic expired-session detection (the native
 * app's `ShellViewModel.reauthRequests`/session-expired prompt is a separate, larger piece
 * tied to detecting a 401 app-wide) — a fresh interactive SSO login here needs no server-side
 * `offline_access` config either, unlike the *silent* refresh-token renewal tracked in the
 * `auth-session-resilience` memory.
 */
class AddServerState(
    private val serverProber: ServerProber,
    private val serverRepository: ServerRepository,
    private val oidcAuthenticator: OidcAuthenticator,
    private val scope: CoroutineScope,
    private val editingId: String? = null,
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

    /** Set once the edit target loads; null while loading and for a plain "add" form.
     * `mutableStateOf`, not a plain `var` — [editingAuthMode] reads it to pick which form UI
     * to show, so Compose needs to observe the write the way it does every other field here. */
    private var loadedServer: Server? by mutableStateOf(null)
        private set

    val isEditing: Boolean get() = editingId != null

    /** The persisted server's auth mode once loaded, null until then (or for a plain "add"
     * form). Drives the reauth-only UI switch (issue #94) — see this class's doc comment. */
    val editingAuthMode: AuthMode? get() = loadedServer?.authMode

    init {
        if (editingId != null) {
            scope.launch {
                serverRepository.get(editingId)?.let { server ->
                    loadedServer = server
                    displayName = server.displayName
                    baseUrl = server.baseUrl
                    username = server.username
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
                    baseUrl = normalizeUrl(baseUrl),
                    type = success?.type ?: base?.type ?: ServerType.GENERIC,
                    // Keep an existing server's auth mode — editing a field shouldn't
                    // silently downgrade an SSO server to password auth. Moot in practice:
                    // this plain save() path is only reachable for NATIVE servers — an OIDC
                    // one uses the reauth-only UI (issue #94), which saves via completeSso()
                    // instead — but matches the native form's rule regardless.
                    authMode = base?.authMode ?: AuthMode.NATIVE,
                    username = username.trim(),
                ),
                // Blank means "keep the stored password" when editing — never overwrite a
                // real secret with an empty one just because the field was left untouched.
                password = password.takeIf { it.isNotBlank() },
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
            baseUrl = normalizeUrl(baseUrl),
            authMode = AuthMode.OIDC,
        )
    }

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
