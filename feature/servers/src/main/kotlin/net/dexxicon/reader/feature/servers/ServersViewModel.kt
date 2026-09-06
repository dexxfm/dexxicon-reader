package net.dexxicon.reader.feature.servers

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

data class ServersUiState(
    val loading: Boolean = true,
    val servers: List<Server> = emptyList(),
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    val uiState: StateFlow<ServersUiState> = serverRepository.servers
        .map { ServersUiState(loading = false, servers = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServersUiState(),
        )

    fun deleteServer(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }
}
