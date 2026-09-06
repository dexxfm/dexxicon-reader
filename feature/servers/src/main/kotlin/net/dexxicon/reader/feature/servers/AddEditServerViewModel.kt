package net.dexxicon.reader.feature.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import net.dexxicon.reader.feature.servers.navigation.AddEditServerRoute
import javax.inject.Inject

data class AddEditServerUiState(
    val editingId: String? = null,
    val displayName: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val passwordTouched: Boolean = false,
    val testState: TestState = TestState.Idle,
    val sso: SsoState = SsoState.Idle,
    val saving: Boolean = false,
    val savedType: ServerType = ServerType.GENERIC,
) {
    val canTest: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val canSave: Boolean
        get() = displayName.isNotBlank() && testState is TestState.Success
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
    data class Ready(val handshake: OidcHandshake) : SsoState
    data object Authorizing : SsoState
    data object Exchanging : SsoState
    data class Error(val message: String) : SsoState
}

@HiltViewModel
class AddEditServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val serverProber: ServerProber,
    private val oidcAuthenticator: OidcAuthenticator,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: String? =
        savedStateHandle.toRoute<AddEditServerRoute>().serverId

    private val _uiState = MutableStateFlow(AddEditServerUiState(editingId = serverId))
    val uiState: StateFlow<AddEditServerUiState> = _uiState.asStateFlow()

    /** Kept across the browser round-trip so the exchange still has the PKCE state. */
    private var pendingHandshake: OidcHandshake? = null

    init {
        if (serverId != null) {
            viewModelScope.launch {
                serverRepository.get(serverId)?.let { server ->
                    _uiState.update {
                        it.copy(
                            displayName = server.displayName,
                            baseUrl = server.baseUrl,
                            username = server.username,
                            savedType = server.type,
                        )
                    }
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value) }

    fun onBaseUrlChange(value: String) = _uiState.update {
        it.copy(baseUrl = value, testState = TestState.Idle, sso = SsoState.Idle)
    }

    fun onUsernameChange(value: String) = _uiState.update {
        it.copy(username = value, testState = TestState.Idle)
    }

    fun onPasswordChange(value: String) = _uiState.update {
        it.copy(password = value, passwordTouched = true, testState = TestState.Idle)
    }

    fun test() {
        val state = _uiState.value
        _uiState.update { it.copy(testState = TestState.Testing) }
        viewModelScope.launch {
            when (val result = serverProber.probe(state.baseUrl, state.username, state.password)) {
                is ServerProbeResult.Success -> _uiState.update {
                    it.copy(
                        testState = TestState.Success(result.detectedType, connectedLabel(result.detectedType)),
                        savedType = result.detectedType,
                        displayName = it.displayName.ifBlank { prettyHost(state.baseUrl) },
                    )
                }
                is ServerProbeResult.InvalidCredentials ->
                    _uiState.update { it.copy(testState = TestState.Failure(result.message)) }
                is ServerProbeResult.Unreachable ->
                    _uiState.update { it.copy(testState = TestState.Failure(result.message)) }
                is ServerProbeResult.NotAServer ->
                    _uiState.update { it.copy(testState = TestState.Failure(result.message)) }
            }
        }
    }

    /** Step 1 of SSO: discover the provider + fetch a state token. */
    fun discoverSso() {
        val state = _uiState.value
        if (state.baseUrl.isBlank()) return
        _uiState.update { it.copy(sso = SsoState.Discovering) }
        viewModelScope.launch {
            when (val result = oidcAuthenticator.beginHandshake(candidateServer())) {
                is Outcome.Success -> {
                    pendingHandshake = result.value
                    _uiState.update { it.copy(sso = SsoState.Ready(result.value)) }
                }
                is Outcome.Failure -> _uiState.update {
                    it.copy(sso = SsoState.Error(result.error.message ?: "SSO is not available"))
                }
            }
        }
    }

    /** The handshake to launch the browser with; null if discovery hasn't run. */
    fun handshakeForAuthorization(): OidcHandshake? = pendingHandshake

    fun onAuthorizing() = _uiState.update { it.copy(sso = SsoState.Authorizing) }

    fun onAuthorizeCancelled() = _uiState.update {
        if (it.sso is SsoState.Exchanging) it else it.copy(sso = SsoState.Idle)
    }

    fun onAuthorizeFailed(message: String) =
        _uiState.update { it.copy(sso = SsoState.Error(message)) }

    /** Step 2 of SSO: exchange the browser code, save the server, finish. */
    fun completeSso(
        redirectUri: String,
        code: String,
        codeVerifier: String,
        nonce: String,
        onSaved: () -> Unit,
    ) {
        val handshake = pendingHandshake ?: run {
            _uiState.update { it.copy(sso = SsoState.Error("Sign-in state was lost — try again")) }
            return
        }
        _uiState.update { it.copy(sso = SsoState.Exchanging) }
        viewModelScope.launch {
            val result = oidcAuthenticator.completeAndSave(
                pendingServer = candidateServer().copy(
                    displayName = _uiState.value.displayName.ifBlank { prettyHost(_uiState.value.baseUrl) },
                ),
                handshake = handshake,
                redirectUri = redirectUri,
                code = code,
                codeVerifier = codeVerifier,
                nonce = nonce,
            )
            when (result) {
                is Outcome.Success -> {
                    pendingHandshake = null
                    onSaved()
                }
                is Outcome.Failure -> _uiState.update {
                    it.copy(sso = SsoState.Error(result.error.message ?: "Sign-in failed"))
                }
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (!state.canSave) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            serverRepository.save(
                server = Server(
                    id = state.editingId ?: "",
                    displayName = state.displayName.trim(),
                    baseUrl = normalizeUrl(state.baseUrl),
                    type = state.savedType,
                    authMode = AuthMode.NATIVE,
                    username = state.username.trim(),
                ),
                password = state.password.takeIf { it.isNotBlank() },
            )
            onSaved()
        }
    }

    private fun candidateServer() = Server(
        id = _uiState.value.editingId ?: "",
        displayName = _uiState.value.displayName.trim(),
        baseUrl = normalizeUrl(_uiState.value.baseUrl),
        authMode = AuthMode.OIDC,
        type = _uiState.value.savedType,
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
