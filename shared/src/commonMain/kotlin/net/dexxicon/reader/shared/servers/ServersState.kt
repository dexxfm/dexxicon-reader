package net.dexxicon.reader.shared.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.Server

/**
 * Backs the servers list's two extras (issue #92) — both already commonMain since Phase 1 and
 * already used by the native app's `feature/settings/ServerListViewModel`, just never wired
 * into `:shared`: the signed-in account per server ([ServerRepository.signedInAs]) and
 * reordering ([ServerRepository.reorder]). No `:core` changes needed, same as #90.
 *
 * Reordering here is plain up/down, not the native Settings screen's long-press drag gesture
 * (`detectDragGesturesAfterLongPress` + manual offset math) — deliberately simpler, same call
 * as tap-confirm-delete over swipe (#72) or explicit search over per-keystroke (#80): custom
 * gesture code is exactly the kind of thing worth avoiding right before a run of PRs that
 * won't get an `ios-ci` check in between.
 */
class ServersState(
    private val serverRepository: ServerRepository,
    private val scope: CoroutineScope,
) {
    /** serverId -> the account the app is signed in as there ("me" endpoint), when known. */
    var accounts: Map<String, String> by mutableStateOf(emptyMap())
        private set

    init {
        scope.launch {
            serverRepository.servers.collectLatest { list ->
                accounts = list.mapNotNull { server ->
                    serverRepository.signedInAs(server)?.let { server.id to it }
                }.toMap()
            }
        }
    }

    fun moveUp(servers: List<Server>, id: String) = move(servers, id, -1)
    fun moveDown(servers: List<Server>, id: String) = move(servers, id, +1)

    private fun move(servers: List<Server>, id: String, delta: Int) {
        val ids = servers.map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        val target = index + delta
        if (index < 0 || target < 0 || target >= ids.size) return
        val moved = ids[index]
        ids[index] = ids[target]
        ids[target] = moved
        scope.launch { serverRepository.reorder(ids) }
    }
}
