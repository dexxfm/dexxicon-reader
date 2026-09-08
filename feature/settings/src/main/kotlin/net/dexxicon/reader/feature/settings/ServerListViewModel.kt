package net.dexxicon.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.Server
import javax.inject.Inject

/** Backs the "Servers" section of Settings — the list, the signed-in account, and remove. */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    val servers: StateFlow<List<Server>> =
        serverRepository.servers
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** serverId → the account the app is signed in as there ("me" endpoint). */
    val accounts: StateFlow<Map<String, String>> =
        serverRepository.servers
            .map { list ->
                list.mapNotNull { server ->
                    serverRepository.signedInAs(server)?.let { server.id to it }
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun remove(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }

    /** Commit a drag-to-reorder result: [orderedIds] top-to-bottom, first = highest priority. */
    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch { serverRepository.reorder(orderedIds) }
    }
}
