package net.dexxicon.reader.core.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
 *
 * Issue #334 — Grimmory rotates the refresh token on every use *and* (since grimmory#2245)
 * stamps each new one with a not-before two minutes out. A refresh sent inside that window
 * 401s without revoking anything, which for an OIDC server used to look exactly like a dead
 * session ("sign in again"). So: a 401 only refreshes when the token it was rejected with is
 * still the current one, a refresh token that isn't usable yet is never sent, and a rotated
 * token that can't be persisted still lives on in memory.
 */
@OptIn(ExperimentalTime::class)
class TokenManager internal constructor(
    private val authClient: NativeAuthClient,
    private val secrets: SessionSecrets,
    private val now: () -> Long,
) {
    constructor(authClient: NativeAuthClient, credentialStore: CredentialStore) :
        this(authClient, CredentialStoreSecrets(credentialStore), { Clock.System.now().toEpochMilliseconds() })

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
        session.refreshToken?.let { persistRefreshToken(serverId, it) }
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

    /**
     * After a 401 on a request sent with [rejectedToken]. Returns the token to retry with —
     * the current one unchanged when refreshing can't help (see the class doc), in which case
     * the caller hands back the 401 rather than retrying.
     */
    suspend fun forceRefresh(server: Server, rejectedToken: String? = null): Outcome<String> =
        lockFor(server.id).withLock {
            val current = bookkeeping.withLock { sessions[server.id] }
            if (current != null && current.expiresAtMillis > now()) {
                // Another request's 401 already renewed the session while this one waited.
                if (rejectedToken != null && current.accessToken != rejectedToken) {
                    return@withLock Outcome.Success(current.accessToken)
                }
                // Too soon to refresh: the server would 401 the refresh itself.
                if (current.refreshToken?.let(::notYetUsable) == true) {
                    return@withLock Outcome.Success(current.accessToken)
                }
            }
            backoffFailure(server)?.let { return@withLock it.map { s -> s.accessToken } }
            // The in-memory refresh token goes first: it's the newest, even if persisting it failed.
            val outcome = obtainSession(server, preferred = current?.refreshToken)
            if (outcome is Outcome.Failure && outcome.error is DexxiconError.Unauthorized) {
                bookkeeping.withLock { sessions.remove(server.id) }
            }
            outcome.also { it.record(server.id) }.map { it.accessToken }
        }

    private suspend fun cachedIfFresh(serverId: String): String? {
        val cached = bookkeeping.withLock { sessions[serverId] } ?: return null
        return cached.accessToken.takeIf { cached.expiresAtMillis > now() }
    }

    /** A cached "re-auth failed recently" result, or null to go ahead and try. */
    private suspend fun backoffFailure(server: Server): Outcome<NativeSession>? {
        val failedAt = bookkeeping.withLock { refreshFailedAt[server.id] } ?: return null
        if (now() - failedAt >= REFRESH_BACKOFF_MILLIS) return null
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
                bookkeeping.withLock { refreshFailedAt[serverId] = now() }
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
        return session == null || session.expiresAtMillis - now() < STALE_WINDOW_MILLIS
    }

    suspend fun invalidate(serverId: String) {
        bookkeeping.withLock {
            sessions.remove(serverId)
            refreshFailedAt.remove(serverId)
        }
    }

    private suspend fun obtainSession(server: Server, preferred: String? = null): Outcome<NativeSession> {
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
        preferred?.let(candidates::add)
        bookkeeping.withLock { sessions[server.id]?.refreshToken }?.let(candidates::add)
        storedRefreshToken(server.id)?.let(candidates::add)
        var tooEarly = false
        for (token in candidates) {
            if (notYetUsable(token)) {
                tooEarly = true
                continue
            }
            val refreshed = authClient.refresh(server, token)
            if (refreshed is Outcome.Success) {
                refreshed.value.refreshToken?.let { persistRefreshToken(server.id, it) }
                clearNeedsSignIn(server.id)
                return refreshed
            }
        }

        return when (server.authMode) {
            AuthMode.NATIVE -> {
                val password = storedPassword(server.id)
                    ?: return Outcome.Failure(
                        DexxiconError.Unauthorized("No stored password for ${server.displayName}"),
                    )
                authClient.login(server, server.username, password)
            }
            // A refresh token that's merely too new still works in a minute: not a dead session.
            AuthMode.OIDC -> if (tooEarly) {
                Outcome.Failure(DexxiconError.Network("${server.displayName} is renewing its session — try again in a moment"))
            } else {
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

    /**
     * True while [refreshToken]'s own `nbf` claim is still ahead. Non-JWT tokens, tokens
     * without `nbf`, and an `nbf` implausibly far out (a badly wrong device clock) count as
     * usable, so this can only ever skip a refresh that the server would have refused anyway.
     */
    private fun notYetUsable(refreshToken: String): Boolean {
        val notBefore = notBeforeMillis(refreshToken) ?: return false
        return notBefore - now() in 1..MAX_NOT_BEFORE_WAIT_MILLIS
    }

    /**
     * Issue #334 — a secure-storage write that fails (the iOS Keychain refuses while the
     * device is locked, for one) must not undo a refresh that already succeeded: the server
     * has revoked the old token, so the new one has to stay in memory at least.
     */
    private suspend fun persistRefreshToken(serverId: String, token: String) {
        try {
            secrets.saveRefreshToken(serverId, token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun storedRefreshToken(serverId: String): String? = try {
        secrets.refreshToken(serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun storedPassword(serverId: String): String? = try {
        secrets.password(serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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

        /** Grimmory's not-before is 2 minutes; anything much further out is a clock problem. */
        const val MAX_NOT_BEFORE_WAIT_MILLIS = 5 * 60 * 1000L
    }
}

/** The secrets [TokenManager] reads and writes — a seam so its tests needn't touch real storage. */
internal interface SessionSecrets {
    suspend fun refreshToken(serverId: String): String?
    suspend fun saveRefreshToken(serverId: String, token: String)
    suspend fun password(serverId: String): String?
}

private class CredentialStoreSecrets(private val store: CredentialStore) : SessionSecrets {
    override suspend fun refreshToken(serverId: String) = store.getRefreshToken(serverId)
    override suspend fun saveRefreshToken(serverId: String, token: String) = store.putRefreshToken(serverId, token)
    override suspend fun password(serverId: String) = store.getPassword(serverId)
}

/** The `nbf` claim of a JWT, in epoch millis, or null if [token] isn't a JWT or has none. */
@OptIn(ExperimentalEncodingApi::class)
internal fun notBeforeMillis(token: String): Long? {
    val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
    return runCatching {
        val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
        Json.parseToJsonElement(json).jsonObject["nbf"]?.jsonPrimitive?.longOrNull
    }.getOrNull()?.times(1000)
}
