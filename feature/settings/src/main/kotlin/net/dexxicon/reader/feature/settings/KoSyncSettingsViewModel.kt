package net.dexxicon.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import javax.inject.Inject

data class KoSyncServerRow(
    val serverId: String,
    val name: String,
    val configured: Boolean,
    val koSyncUrl: String,
    val koSyncUsername: String,
    /** null = not checked, true/false = last verify result */
    val verified: Boolean? = null,
    val verifying: Boolean = false,
)

@HiltViewModel
class KoSyncSettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val koSync: KoSyncRepository,
) : ViewModel() {

    private val verifyState = MutableStateFlow<Map<String, Pair<Boolean?, Boolean>>>(emptyMap())

    val rows: StateFlow<List<KoSyncServerRow>> =
        combine(serverRepository.servers, verifyState) { servers, verify ->
            servers.map { s ->
                val (verified, verifying) = verify[s.id] ?: (null to false)
                KoSyncServerRow(
                    serverId = s.id,
                    name = s.displayName,
                    configured = !s.koSyncUrl.isNullOrBlank() && !s.koSyncUsername.isNullOrBlank(),
                    koSyncUrl = s.koSyncUrl.orEmpty(),
                    koSyncUsername = s.koSyncUsername.orEmpty(),
                    verified = verified,
                    verifying = verifying,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(serverId: String, url: String, username: String, password: String) {
        viewModelScope.launch {
            val server = serverRepository.get(serverId) ?: return@launch
            serverRepository.save(
                server = server.copy(
                    koSyncUrl = url.trim().trimEnd('/').takeIf { it.isNotBlank() },
                    koSyncUsername = username.trim().takeIf { it.isNotBlank() },
                ),
                password = null,
                koSyncPassword = password.takeIf { it.isNotBlank() },
            )
            verify(serverId)
        }
    }

    fun verify(serverId: String) {
        viewModelScope.launch {
            setVerify(serverId, null, true)
            val server = serverRepository.get(serverId)
            val ok = server != null && koSync.verify(server)
            setVerify(serverId, ok, false)
        }
    }

    private fun setVerify(serverId: String, verified: Boolean?, verifying: Boolean) {
        verifyState.value = verifyState.value.toMutableMap().apply {
            put(serverId, verified to verifying)
        }
    }
}
