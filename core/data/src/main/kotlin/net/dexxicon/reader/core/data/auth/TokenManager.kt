package net.dexxicon.reader.core.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.map
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.auth.NativeSession
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the live session per server in memory, refreshing (or re-authenticating) when it
 * expires. Secrets are read from [CredentialStore] only when a fresh login is required.
 */
@Singleton
class TokenManager @Inject constructor(
    private val authClient: NativeAuthClient,
    private val credentialStore: CredentialStore,
) {
    private val sessions = ConcurrentHashMap<String, NativeSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Prime the cache with a freshly obtained session (e.g. right after an OIDC exchange). */
    suspend fun seedSession(serverId: String, session: NativeSession) {
        sessions[serverId] = session
        session.refreshToken?.let { credentialStore.putRefreshToken(serverId, it) }
    }

    suspend fun bearerToken(server: Server): Outcome<String> {
        require(server.authMode == AuthMode.NATIVE || server.authMode == AuthMode.OIDC)

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
            obtainSession(server).also { it.storeIfSuccess(server.id) }.map { it.accessToken }
        }
    }

    suspend fun forceRefresh(server: Server): Outcome<String> = lockFor(server.id).withLock {
        sessions.remove(server.id)
        obtainSession(server).also { it.storeIfSuccess(server.id) }.map { it.accessToken }
    }

    fun invalidate(serverId: String) {
        sessions.remove(serverId)
    }

    private suspend fun obtainSession(server: Server): Outcome<NativeSession> {
        // BookOrbit keeps the refresh token in an HttpOnly cookie (persisted in the shared
        // cookie jar), so there is nothing to pass in the body.
        if (server.type == ServerType.BOOKORBIT) {
            val refreshed = authClient.refreshViaCookie(server)
            if (refreshed is Outcome.Success) return refreshed
        }

        val refreshToken = sessions[server.id]?.refreshToken
            ?: credentialStore.getRefreshToken(server.id)
        if (refreshToken != null) {
            val refreshed = authClient.refresh(server, refreshToken)
            if (refreshed is Outcome.Success) {
                refreshed.value.refreshToken?.let {
                    credentialStore.putRefreshToken(server.id, it)
                }
                return refreshed
            }
        }

        return when (server.authMode) {
            AuthMode.NATIVE -> {
                val password = credentialStore.getPassword(server.id)
                    ?: return Outcome.Failure(
                        DexxiconError.Unauthorized("No stored password for ${server.displayName}"),
                    )
                authClient.login(server, server.username, password)
            }
            AuthMode.OIDC -> Outcome.Failure(
                DexxiconError.Unauthorized("Your ${server.displayName} session expired — sign in again"),
            )
            AuthMode.BASIC -> Outcome.Failure(
                DexxiconError.Unsupported("BASIC auth does not use tokens"),
            )
        }
    }

    private fun Outcome<NativeSession>.storeIfSuccess(serverId: String) {
        if (this is Outcome.Success) sessions[serverId] = value
    }

    private fun lockFor(serverId: String): Mutex = locks.getOrPut(serverId) { Mutex() }
}
