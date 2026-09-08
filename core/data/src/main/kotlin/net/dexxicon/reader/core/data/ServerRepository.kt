package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.entity.ServerEntity
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.user.NativeUserApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val credentialStore: CredentialStore,
    private val tokenManager: TokenManager,
    private val nativeUserApi: NativeUserApi,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    val servers: Flow<List<Server>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    val serverCount: Flow<Int> = serverDao.count()

    fun server(id: String): Flow<Server?> =
        serverDao.observe(id).map { it?.toDomain() }

    suspend fun get(id: String): Server? = serverDao.findById(id)?.toDomain()

    /** Insert or update a server. Pass [password] to (re)store the secret. */
    suspend fun save(server: Server, password: String?, koSyncPassword: String? = null): Server {
        val withId = if (server.id.isBlank()) {
            server.copy(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                sortOrder = serverDao.getAll().size,
            )
        } else {
            server
        }
        serverDao.upsert(ServerEntity.fromDomain(withId))
        if (password != null) {
            credentialStore.putPassword(withId.id, password)
            tokenManager.invalidate(withId.id)
        }
        if (koSyncPassword != null) {
            credentialStore.putSyncSecret(withId.id, KoSyncRepository.KOSYNC_PROVIDER, koSyncPassword)
        }
        return withId
    }

    /**
     * The account the app is authenticated as on [server], from the server's "me" endpoint —
     * so Settings can show it and account mix-ups (e.g. OIDC provisioning a second user)
     * are visible. Null when it can't be determined or the server isn't a native one.
     */
    suspend fun signedInAs(server: Server): String? = withContext(io) {
        val path = when (server.type) {
            ServerType.BOOKORBIT -> "/api/v1/auth/me"
            ServerType.GRIMMORY -> "/api/v1/users/me"
            else -> return@withContext server.username.takeIf { it.isNotBlank() }
        }
        runCatching { nativeUserApi.me(server.resolve(path)) }.getOrNull()?.let { me ->
            listOfNotNull(me.username, me.name, me.email)
                .firstOrNull { it.isNotBlank() }
        }
    }

    /** Persist a new display priority. [orderedIds] is the full server list, first = highest. */
    suspend fun reorder(orderedIds: List<String>) = withContext(io) {
        serverDao.applyOrder(orderedIds)
    }

    suspend fun delete(id: String) {
        serverDao.deleteById(id)
        credentialStore.clear(id)
        tokenManager.invalidate(id)
    }
}
