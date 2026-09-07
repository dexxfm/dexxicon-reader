package net.dexxicon.reader.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a "re-authenticate this server" request from a notification tap (handled in
 * [net.dexxicon.reader.MainActivity]) into the Compose nav host in `DexxiconApp`.
 */
@Singleton
class ReauthCoordinator @Inject constructor() {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    fun request(serverId: String) {
        _requests.tryEmit(serverId)
    }
}
