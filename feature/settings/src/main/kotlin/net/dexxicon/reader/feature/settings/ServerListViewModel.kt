package net.dexxicon.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.Server
import javax.inject.Inject

/** Backs the "Servers" section of Settings — the list plus remove. */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
) : ViewModel() {

    val servers: StateFlow<List<Server>> =
        serverRepository.servers
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: String) {
        viewModelScope.launch { serverRepository.delete(id) }
    }
}
