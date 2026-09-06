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
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerProbeResult
import net.dexxicon.reader.core.model.ServerType
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
    val saving: Boolean = false,
    val savedType: ServerType = ServerType.GENERIC,
) {
    val canTest: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() &&
            (password.isNotBlank() || (editingId != null && !passwordTouched))

    val canSave: Boolean
        get() = displayName.isNotBlank() && testState is TestState.Success
}

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val type: ServerType, val detail: String) : TestState
    data class Failure(val message: String) : TestState
}

@HiltViewModel
class AddEditServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val serverProber: ServerProber,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: String? =
        savedStateHandle.toRoute<AddEditServerRoute>().serverId

    private val _uiState = MutableStateFlow(AddEditServerUiState(editingId = serverId))
    val uiState: StateFlow<AddEditServerUiState> = _uiState.asStateFlow()

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
        it.copy(baseUrl = value, testState = TestState.Idle)
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
                        testState = TestState.Success(
                            result.detectedType,
                            result.detectedType.name.lowercase()
                                .replaceFirstChar(Char::uppercase) + " · connected",
                        ),
                        savedType = result.detectedType,
                        displayName = it.displayName.ifBlank {
                            result.serverName ?: prettyHost(state.baseUrl)
                        },
                    )
                }
                is ServerProbeResult.InvalidCredentials -> _uiState.update {
                    it.copy(testState = TestState.Failure(result.message))
                }
                is ServerProbeResult.Unreachable -> _uiState.update {
                    it.copy(testState = TestState.Failure(result.message))
                }
                is ServerProbeResult.NotAServer -> _uiState.update {
                    it.copy(testState = TestState.Failure(result.message))
                }
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (!state.canSave) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val normalizedUrl = normalizeUrl(state.baseUrl)
            val server = Server(
                id = state.editingId ?: "",
                displayName = state.displayName.trim(),
                baseUrl = normalizedUrl,
                type = state.savedType,
                authMode = AuthMode.NATIVE,
                username = state.username.trim(),
            )
            serverRepository.save(
                server = server,
                password = state.password.takeIf { it.isNotBlank() },
            )
            onSaved()
        }
    }

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
