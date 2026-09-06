package net.dexxicon.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import net.dexxicon.reader.core.datastore.SyncStateStore
import javax.inject.Inject

data class SyncServerRow(
    val serverId: String,
    val name: String,
    /** BookOrbit / Grimmory sync through their own API; generic OPDS servers use KOReader. */
    val usesNative: Boolean,
    val configured: Boolean,
    /** The by-convention kosync URL for this server family (generic servers only). */
    val assumedUrl: String,
    /** A custom override URL, or "" when the assumed one is used. */
    val customUrl: String,
    val koSyncUsername: String,
    /** null = not checked, true/false = last verify result (generic servers only). */
    val verified: Boolean? = null,
    val verifying: Boolean = false,
    /** Epoch millis of the last successful progress sync, or null. */
    val lastSyncedAt: Long? = null,
) {
    val effectiveUrl: String get() = customUrl.trim().trimEnd('/').ifBlank { assumedUrl }
}

@HiltViewModel
class KoSyncSettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val koSync: KoSyncRepository,
    private val progressRepository: ReadingProgressRepository,
    syncStateStore: SyncStateStore,
) : ViewModel() {

    private val verifyState = MutableStateFlow<Map<String, Pair<Boolean?, Boolean>>>(emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    val rows: StateFlow<List<SyncServerRow>> =
        combine(
            serverRepository.servers,
            verifyState,
            syncStateStore.lastSyncedAt,
        ) { servers, verify, lastSynced ->
            servers.map { s ->
                val (verified, verifying) = verify[s.id] ?: (null to false)
                SyncServerRow(
                    serverId = s.id,
                    name = s.displayName,
                    usesNative = s.type.supportsNativeApi,
                    configured = s.type.supportsNativeApi || !s.koSyncUsername.isNullOrBlank(),
                    assumedUrl = s.assumedKoSyncUrl,
                    customUrl = s.koSyncUrl.orEmpty(),
                    koSyncUsername = s.koSyncUsername.orEmpty(),
                    verified = verified,
                    verifying = verifying,
                    lastSyncedAt = lastSynced[s.id],
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [customUrl] blank → use the assumed URL (stored as null). */
    fun save(serverId: String, customUrl: String, username: String, password: String) {
        viewModelScope.launch {
            val server = serverRepository.get(serverId) ?: return@launch
            serverRepository.save(
                server = server.copy(
                    koSyncUrl = customUrl.trim().trimEnd('/').takeIf { it.isNotBlank() },
                    koSyncUsername = username.trim().takeIf { it.isNotBlank() },
                ),
                password = null,
                koSyncPassword = password.takeIf { it.isNotBlank() },
            )
            verify(serverId)
        }
    }

    /**
     * Pull-to-refresh on Settings: reconcile reading progress with every server (native or
     * kosync, both directions) and re-verify the KOReader accounts.
     */
    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { progressRepository.syncProgress() }
            serverRepository.servers.first()
                .filter { !it.type.supportsNativeApi && !it.koSyncUsername.isNullOrBlank() }
                .forEach { server ->
                    setVerify(server.id, null, true)
                    val ok = runCatching { koSync.verify(server) }.getOrDefault(false)
                    setVerify(server.id, ok, false)
                }
            _refreshing.value = false
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
