package net.dexxicon.reader.core.data.auth

import android.util.Base64
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.security.CredentialStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a request URL to a saved server (by host:port) and returns Basic or Bearer.
 * Holds a plain snapshot of servers so it can answer synchronously on OkHttp threads.
 */
@Singleton
class AuthHeaderProviderImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val credentialStore: CredentialStore,
    private val tokenManager: TokenManager,
    @ApplicationScope scope: CoroutineScope,
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
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    private fun bearerHeader(server: Server): String? =
        (runBlocking { tokenManager.bearerToken(server) } as? Outcome.Success)
            ?.let { "Bearer ${it.value}" }

    private fun authorityOf(baseUrl: String): String {
        val parsed = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        return "${parsed.host}:${parsed.port}"
    }
}
