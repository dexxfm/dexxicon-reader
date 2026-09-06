package net.dexxicon.reader.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.Server
import javax.inject.Inject

data class ServersUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val servers: List<Server> = emptyList(),
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<ServersUiState> =
        combine(serverRepository.servers, refreshing) { servers, isRefreshing ->
            ServersUiState(loading = false, refreshing = isRefreshing, servers = servers)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServersUiState(),
        )

    fun deleteServer(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            // The server list is already live from the DB; this just re-checks it and
            // gives the pull gesture something to settle against.
            runCatching { serverRepository.servers }
            delay(450)
            refreshing.value = false
        }
    }
}
