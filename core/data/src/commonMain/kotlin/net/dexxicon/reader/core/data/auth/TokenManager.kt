package net.dexxicon.reader.core.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.map
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.auth.NativeSession

/**
 * Holds the live session per server in memory, refreshing (or re-authenticating) when it
 * expires. Secrets are read from [CredentialStore] only when a fresh login is required.
 *
 * OIDC sessions can only be refreshed silently while their refresh token is still valid, so
 * [refreshIfStale] renews them ahead of expiry (driven by `SessionRefreshWorker` and the
 * progress sync), and [needsSignIn] surfaces the ones that have nonetheless dead-ended so
 * the UI can prompt for a re-sign-in instead of failing silently.
 */
@OptIn(ExperimentalTime::class)
class TokenManager(
    private val authClient: NativeAuthClient,
    private val credentialStore: CredentialStore,
) {
    private val sessions = mutableMapOf<String, NativeSession>()
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * serverId -> when its last re-auth attempt failed. A dead session makes every pending
     * request 401 at once, and each 401 asks for a refresh; without this every one of them
     * hits `/auth/refresh` again. Cleared on a successful sign-in.
     */
    private val refreshFailedAt = mutableMapOf<String, Long>()

    /**
     * Guards [sessions]/[refreshFailedAt]/[locks] themselves — cheap, always-brief
     * bookkeeping touches. `java.util.concurrent.ConcurrentHashMap` isn't available outside
     * the JVM target, so plain maps plus this one small Mutex take its place. Separate from
     * each server's own [Mutex] (held in [locks]), which wraps the actual network call so
     * one server refreshing never blocks another.
     */
    private val bookkeeping = Mutex()

    private val _needsSignIn = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of OIDC servers whose session expired and can't be refreshed without the user. */
    val needsSignIn: StateFlow<Set<String>> = _needsSignIn.asStateFlow()

    /** Prime the cache with a freshly obtained session (e.g. right after an OIDC exchange). */
    suspend fun seedSession(serverId: String, session: NativeSession) {
        bookkeeping.withLock {
            sessions[serverId] = session
            refreshFailedAt.remove(serverId)
        }
        session.refreshToken?.let { credentialStore.putRefreshToken(serverId, it) }
        clearNeedsSignIn(serverId)
    }

    suspend fun bearerToken(server: Server): Outcome<String> {
        require(server.authMode == AuthMode.NATIVE || server.authMode == AuthMode.OIDC)

        cachedIfFresh(server.id)?.let { return Outcome.Success(it) }

        return lockFor(server.id).withLock {
            cachedIfFresh(server.id)?.let { return@withLock Outcome.Success(it) }
            backoffFailure(server)?.let { return@withLock it.map { s -> s.accessToken } }
            obtainSession(server).also { it.record(server.id) }.map { it.accessToken }
        }
    }

    suspend fun forceRefresh(server: Server): Outcome<String> = lockFor(server.id).withLock {
        backoffFailure(server)?.let { return@withLock it.map { s -> s.accessToken } }
        bookkeeping.withLock { sessions.remove(server.id) }
        obtainSession(server).also { it.record(server.id) }.map { it.accessToken }
    }

    private suspend fun cachedIfFresh(serverId: String): String? {
        val cached = bookkeeping.withLock { sessions[serverId] } ?: return null
        return cached.accessToken.takeIf {
            cached.expiresAtMillis > Clock.System.now().toEpochMilliseconds()
        }
    }

    /** A cached "re-auth failed recently" result, or null to go ahead and try. */
    private suspend fun backoffFailure(server: Server): Outcome<NativeSession>? {
        val failedAt = bookkeeping.withLock { refreshFailedAt[server.id] } ?: return null
        if (Clock.System.now().toEpochMilliseconds() - failedAt >= REFRESH_BACKOFF_MILLIS) return null
        return Outcome.Failure(
            DexxiconError.Unauthorized("Your ${server.displayName} session expired — sign in again"),
        )
    }

    private suspend fun Outcome<NativeSession>.record(serverId: String) {
        storeIfSuccess(serverId)
        when {
            this is Outcome.Success -> bookkeeping.withLock { refreshFailedAt.remove(serverId) }
            // Only an actual auth rejection backs off. A network blip should retry freely.
            this is Outcome.Failure && error is DexxiconError.Unauthorized ->
                bookkeeping.withLock {
                    refreshFailedAt[serverId] = Clock.System.now().toEpochMilliseconds()
                }
        }
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
        if (!isStale(server.id)) return
        if (backoffFailure(server) != null) return
        lockFor(server.id).withLock {
            if (!isStale(server.id)) return@withLock
            obtainSession(server).also { it.record(server.id) }
        }
    }

    private suspend fun isStale(serverId: String): Boolean {
        val session = bookkeeping.withLock { sessions[serverId] }
        return session == null ||
            session.expiresAtMillis - Clock.System.now().toEpochMilliseconds() < STALE_WINDOW_MILLIS
    }

    suspend fun invalidate(serverId: String) {
        bookkeeping.withLock {
            sessions.remove(serverId)
            refreshFailedAt.remove(serverId)
        }
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
        bookkeeping.withLock { sessions[server.id]?.refreshToken }?.let(candidates::add)
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

    private suspend fun Outcome<NativeSession>.storeIfSuccess(serverId: String) {
        if (this is Outcome.Success) {
            bookkeeping.withLock { sessions[serverId] = value }
            clearNeedsSignIn(serverId)
        }
    }

    private fun markNeedsSignIn(serverId: String) =
        _needsSignIn.update { if (serverId in it) it else it + serverId }

    private fun clearNeedsSignIn(serverId: String) =
        _needsSignIn.update { if (serverId in it) it - serverId else it }

    private suspend fun lockFor(serverId: String): Mutex =
        bookkeeping.withLock { locks.getOrPut(serverId) { Mutex() } }

    private companion object {
        /** Refresh proactively once the access token has less than this left. */
        const val STALE_WINDOW_MILLIS = 30 * 60 * 1000L

        /** After a failed re-auth, don't try again for this long (kills 401 refresh storms). */
        const val REFRESH_BACKOFF_MILLIS = 30 * 1000L
    }
}
