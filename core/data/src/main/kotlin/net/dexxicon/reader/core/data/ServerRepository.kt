package net.dexxicon.reader.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.entity.ServerEntity
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.security.CredentialStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val credentialStore: CredentialStore,
    private val tokenManager: TokenManager,
) {
    val servers: Flow<List<Server>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    val serverCount: Flow<Int> = serverDao.count()

    fun server(id: String): Flow<Server?> =
        serverDao.observe(id).map { it?.toDomain() }

    suspend fun get(id: String): Server? = serverDao.findById(id)?.toDomain()

    /** Insert or update a server. Pass [password] to (re)store the secret. */
    suspend fun save(server: Server, password: String?): Server {
        val withId = if (server.id.isBlank()) {
            server.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        } else {
            server
        }
        serverDao.upsert(ServerEntity.fromDomain(withId))
        if (password != null) {
            credentialStore.putPassword(withId.id, password)
            tokenManager.invalidate(withId.id)
        }
        return withId
    }

    suspend fun delete(id: String) {
        serverDao.deleteById(id)
        credentialStore.clear(id)
        tokenManager.invalidate(id)
    }
}
