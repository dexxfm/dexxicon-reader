package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.database.dao.BookmarkDao
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.entity.ServerEntity
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.user.NativeUserApi

/**
 * The `provider` key [CredentialStore] uses for KOReader kosync credentials. Lives here
 * (rather than as a companion constant on `KoSyncRepository`, its original home) because
 * `KoSyncRepository` stays Android-only — it has real Android-specific code (`Context`,
 * `Settings.Secure`, raw `OkHttpClient` range reads) — while [ServerRepository], which also
 * needs this value, is commonMain. A plain string constant has no such constraint either way.
 */
const val KOSYNC_CREDENTIAL_PROVIDER = "kosync"

/**
 * [io] is a plain, unqualified [CoroutineDispatcher] rather than `@Dispatcher(IO)` — that
 * qualifier annotation is `javax.inject`-based (Hilt/androidMain-only) and can't live on a
 * commonMain constructor. The `:app`-hosted provider resolves the qualified binding and
 * passes the instance through instead.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class ServerRepository(
    private val serverDao: ServerDao,
    private val credentialStore: CredentialStore,
    private val tokenManager: TokenManager,
    private val nativeUserApi: NativeUserApi,
    private val io: CoroutineDispatcher,
    // issue #206 — deleted-server cascade cleanup. Raw DAOs (not ReadingProgressRepository
    // etc.) specifically to avoid a circular dependency: ReadingProgressRepository already
    // depends on this class.
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingProgressDao: ReadingProgressDao,
    private val downloadRepository: DownloadRepository,
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
                id = Uuid.random().toString(),
                createdAt = Clock.System.now().toEpochMilliseconds(),
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
            credentialStore.putSyncSecret(withId.id, KOSYNC_CREDENTIAL_PROVIDER, koSyncPassword)
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

    /**
     * issue #206 — deleting a server used to leave every bookmark/highlight/reading-progress/
     * download row tied to it behind forever: nothing else referenced [id] again, but nothing
     * ever deleted them either (no DB-level cascade exists on any of these tables). Home's
     * Continue reading/listening and Downloaded shelves in particular read *all* rows with no
     * server-list filtering of their own, and both `ReadingProgressEntity`/`DownloadEntity`
     * cache their own title/cover — so an orphaned row from a removed server kept surfacing
     * there indefinitely, showing stale data for a book whose server no longer exists.
     */
    suspend fun delete(id: String) = withContext(io) {
        serverDao.deleteById(id)
        credentialStore.clear(id)
        tokenManager.invalidate(id)
        bookmarkDao.deleteForServer(id)
        highlightDao.deleteForServer(id)
        readingProgressDao.deleteForServer(id)
        downloadRepository.removeAllForServer(id)
    }
}
