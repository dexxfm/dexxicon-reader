package net.dexxicon.reader.shared.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerProbeResult
import net.dexxicon.reader.core.model.ServerType

/**
 * The add-server form's state + behaviour — the shared-UI equivalent of the native
 * `AddEditServerViewModel` (`feature/servers`), scoped down to what Slice 1 needs: native
 * (username/password) sign-in only, adding rather than editing, no SSO. There's no
 * `ViewModel`/Hilt here (see [net.dexxicon.reader.shared.di.AppContainer]'s doc comment) —
 * a plain class holding the [scope] the caller got from `rememberCoroutineScope()` plays the
 * same role.
 */
class AddServerState(
    private val serverProber: ServerProber,
    private val serverRepository: ServerRepository,
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

    /** Adding a server requires a successful connection test first — same rule as the native
     * form, so a server never gets saved with a type/auth-mode nobody actually verified. */
    val canTest: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
            testState != TestState.Testing
    val canSave: Boolean
        get() = displayName.isNotBlank() && baseUrl.isNotBlank() &&
            testState is TestState.Success && !saving

    fun onDisplayNameChange(value: String) { displayName = value }
    fun onBaseUrlChange(value: String) { baseUrl = value; testState = TestState.Idle }
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
