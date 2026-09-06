package net.dexxicon.reader.core.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.map
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.auth.NativeSession
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the live native session per server in memory, refreshing (or re-logging-in) when it
 * expires. Passwords are read from [CredentialStore] only when a fresh login is needed.
 */
@Singleton
class TokenManager @Inject constructor(
    private val authClient: NativeAuthClient,
    private val credentialStore: CredentialStore,
) {
    private val sessions = ConcurrentHashMap<String, NativeSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Bearer token for [server], logging in / refreshing as needed. */
    suspend fun bearerToken(server: Server): Outcome<String> {
        require(server.authMode == AuthMode.NATIVE)

        sessions[server.id]?.let { cached ->
            if (cached.expiresAtMillis > System.currentTimeMillis()) {
                return Outcome.Success(cached.accessToken)
            }
        }

        return lockFor(server.id).withLock {
            sessions[server.id]?.let { cached ->
                if (cached.expiresAtMillis > System.currentTimeMillis()) {
                    return@withLock Outcome.Success(cached.accessToken)
                }
            }
            obtainSession(server).also { it.storeIfSuccess(server.id) }
                .map { it.accessToken }
        }
    }

    /** Force a new session (used after a 401). */
    suspend fun forceRefresh(server: Server): Outcome<String> = lockFor(server.id).withLock {
        sessions.remove(server.id)
        obtainSession(server).also { it.storeIfSuccess(server.id) }.map { it.accessToken }
    }

    fun invalidate(serverId: String) {
        sessions.remove(serverId)
    }

    private suspend fun obtainSession(server: Server): Outcome<NativeSession> {
        val refreshToken = sessions[server.id]?.refreshToken
        if (refreshToken != null) {
            val refreshed = authClient.refresh(server, refreshToken)
            if (refreshed is Outcome.Success) return refreshed
        }
        val password = credentialStore.getPassword(server.id)
            ?: return Outcome.Failure(
                DexxiconError.Unauthorized("No stored password for ${server.displayName}"),
            )
        return authClient.login(server, server.username, password)
    }

    private fun Outcome<NativeSession>.storeIfSuccess(serverId: String) {
        if (this is Outcome.Success) sessions[serverId] = value
    }

    private fun lockFor(serverId: String): Mutex =
        locks.getOrPut(serverId) { Mutex() }
}
