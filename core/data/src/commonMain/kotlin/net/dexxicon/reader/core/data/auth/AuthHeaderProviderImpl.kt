package net.dexxicon.reader.core.data.auth

import io.ktor.http.Url
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.security.CredentialStore

/**
 * Resolves a request URL to a saved server (by host:port) and returns Basic or Bearer. Holds
 * a plain snapshot of servers so it can answer synchronously — [AuthHeaderProvider]'s
 * contract is deliberately non-suspend so it can be called from OkHttp's synchronous
 * `Interceptor` chain (androidMain's `AuthInterceptor`) as well as from Ktor's `HttpSend`
 * plugin (`:core:network`'s `installAuthHeaderPlugin`, commonMain) — the `runBlocking` calls
 * below already existed on Android for exactly this reason; this port doesn't introduce them,
 * it just means they now also run on iOS.
 *
 * [scope] is a plain, unqualified `CoroutineScope` rather than `@ApplicationScope` — same
 * reason as every other Phase 1/2 qualifier: `javax.inject` doesn't exist on iOS, so the
 * qualified binding is resolved at the `:app`-hosted `@Provides` call site instead.
 *
 * Two real platform swaps from the original androidMain version: `android.util.Base64` ->
 * the stdlib's own `kotlin.io.encoding.Base64` (already used for `:shared`'s `Pkce`), and
 * `okhttp3.HttpUrl.toHttpUrlOrNull()` (parsing a base URL's host:port) -> Ktor's
 * multiplatform `Url`.
 */
@OptIn(ExperimentalEncodingApi::class)
class AuthHeaderProviderImpl(
    private val serverDao: ServerDao,
    private val credentialStore: CredentialStore,
    private val tokenManager: TokenManager,
    scope: CoroutineScope,
) : AuthHeaderProvider {

    @Volatile
    private var serversByAuthority: Map<String, Server> = emptyMap()

    init {
        scope.launch {
            serverDao.observeAll().collect { entities ->
                serversByAuthority = entities
                    .map { it.toDomain() }
                    .associateBy { authorityOf(it.baseUrl) }
            }
        }
    }

    override fun authHeader(url: Url): String? {
        val server = serverFor(url) ?: return null
        return when (server.authMode) {
            AuthMode.BASIC -> basicHeader(server)
            AuthMode.NATIVE, AuthMode.OIDC -> bearerHeader(server)
        }
    }

    override fun refreshAuthHeader(url: Url): String? {
        val server = serverFor(url) ?: return null
        if (server.authMode == AuthMode.BASIC) return null
        return (runBlocking { tokenManager.forceRefresh(server) } as? Outcome.Success)
            ?.let { "Bearer ${it.value}" }
    }

    private fun serverFor(url: Url): Server? {
        lookup(serversByAuthority, url)?.let { return it }
        // Cold-start race: the observeAll() collector may not have emitted yet. Load once.
        val fresh = runBlocking { serverDao.getAll() }
            .map { it.toDomain() }
            .associateBy { authorityOf(it.baseUrl) }
        serversByAuthority = fresh
        return lookup(fresh, url)
    }

    private fun lookup(map: Map<String, Server>, url: Url): Server? =
        map["${url.host}:${url.port}"]
            ?: map.values.firstOrNull { url.toString().startsWith(it.normalizedBaseUrl) }

    private fun basicHeader(server: Server): String? {
        val password = runBlocking { credentialStore.getPassword(server.id) } ?: return null
        val raw = "${server.username}:$password"
        return "Basic " + Base64.Default.encode(raw.encodeToByteArray())
    }

    private fun bearerHeader(server: Server): String? =
        (runBlocking { tokenManager.bearerToken(server) } as? Outcome.Success)
            ?.let { "Bearer ${it.value}" }

    private fun authorityOf(baseUrl: String): String =
        runCatching { Url(baseUrl) }.getOrNull()?.let { "${it.host}:${it.port}" } ?: baseUrl
}
