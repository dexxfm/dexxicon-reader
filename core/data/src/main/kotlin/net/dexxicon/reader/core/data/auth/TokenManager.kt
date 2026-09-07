package net.dexxicon.reader.core.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *
 * OIDC sessions can only be refreshed silently while their refresh token is still valid, so
 * [refreshIfStale] renews them ahead of expiry (driven by `SessionRefreshWorker` and the
 * progress sync), and [needsSignIn] surfaces the ones that have nonetheless dead-ended so
 * the UI can prompt for a re-sign-in instead of failing silently.
 */
@Singleton
class TokenManager @Inject constructor(
    private val authClient: NativeAuthClient,
    private val credentialStore: CredentialStore,
) {
    private val sessions = ConcurrentHashMap<String, NativeSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _needsSignIn = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of OIDC servers whose session expired and can't be refreshed without the user. */
    val needsSignIn: StateFlow<Set<String>> = _needsSignIn.asStateFlow()

    /** Prime the cache with a freshly obtained session (e.g. right after an OIDC exchange). */
    suspend fun seedSession(serverId: String, session: NativeSession) {
        sessions[serverId] = session
        session.refreshToken?.let { credentialStore.putRefreshToken(serverId, it) }
        clearNeedsSignIn(serverId)
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

    /**
     * Renew [server]'s session now if it is missing or within [STALE_WINDOW_MILLIS] of
     * expiry. Called off the critical path (background worker, before a sync) so an idle
     * OIDC session is refreshed while its refresh token is still good rather than
     * dead-ending on the next real request. No-op for BASIC servers; failures are swallowed
     * (the on-demand path still runs and reports the error the normal way).
     */
    suspend fun refreshIfStale(server: Server) {
        if (server.authMode != AuthMode.NATIVE && server.authMode != AuthMode.OIDC) return
        if (!isStale(sessions[server.id])) return
        lockFor(server.id).withLock {
            if (!isStale(sessions[server.id])) return@withLock
            obtainSession(server).also { it.storeIfSuccess(server.id) }
        }
    }

    private fun isStale(session: NativeSession?): Boolean =
        session == null ||
            session.expiresAtMillis - System.currentTimeMillis() < STALE_WINDOW_MILLIS

    fun invalidate(serverId: String) {
        sessions.remove(serverId)
    }

    private suspend fun obtainSession(server: Server): Outcome<NativeSession> {
        // BookOrbit keeps the refresh token in an HttpOnly cookie (persisted in the shared
        // cookie jar), so there is nothing to pass in the body.
        if (server.type == ServerType.BOOKORBIT) {
            val refreshed = authClient.refreshViaCookie(server)
            if (refreshed is Outcome.Success) {
                clearNeedsSignIn(server.id)
                return refreshed
            }
        }

        // Grimmory rotates the refresh token on every use, so the in-memory copy and the
        // persisted copy can drift apart if a previous refresh half-completed. Try each
        // distinct token before giving up.
        val candidates = LinkedHashSet<String>()
        sessions[server.id]?.refreshToken?.let(candidates::add)
        credentialStore.getRefreshToken(server.id)?.let(candidates::add)
        for (token in candidates) {
            val refreshed = authClient.refresh(server, token)
            if (refreshed is Outcome.Success) {
                refreshed.value.refreshToken?.let {
                    credentialStore.putRefreshToken(server.id, it)
                }
                clearNeedsSignIn(server.id)
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
            AuthMode.OIDC -> {
                markNeedsSignIn(server.id)
                Outcome.Failure(
                    DexxiconError.Unauthorized("Your ${server.displayName} session expired — sign in again"),
                )
            }
            AuthMode.BASIC -> Outcome.Failure(
                DexxiconError.Unsupported("BASIC auth does not use tokens"),
            )
        }
    }

    private fun Outcome<NativeSession>.storeIfSuccess(serverId: String) {
        if (this is Outcome.Success) {
            sessions[serverId] = value
            clearNeedsSignIn(serverId)
        }
    }

    private fun markNeedsSignIn(serverId: String) =
        _needsSignIn.update { if (serverId in it) it else it + serverId }

    private fun clearNeedsSignIn(serverId: String) =
        _needsSignIn.update { if (serverId in it) it - serverId else it }

    private fun lockFor(serverId: String): Mutex = locks.getOrPut(serverId) { Mutex() }

    private companion object {
        /** Refresh proactively once the access token has less than this left. */
        const val STALE_WINDOW_MILLIS = 30 * 60 * 1000L
    }
}
