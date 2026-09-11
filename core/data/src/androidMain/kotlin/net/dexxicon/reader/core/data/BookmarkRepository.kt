package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.database.dao.BookmarkDao
import net.dexxicon.reader.core.database.entity.BookmarkEntity
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.bookmark.BookOrbitBookmarkBody
import net.dexxicon.reader.core.serverapi.bookmark.BookmarkApi
import net.dexxicon.reader.core.serverapi.bookmark.GrimmoryBookmarkBody
import org.json.JSONObject
import io.ktor.client.plugins.ResponseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EPUB bookmarks. The source of truth is the local `bookmarks` table; a best-effort sync
 * mirrors them to the server's bookmark API (both BookOrbit and Grimmory have full CRUD).
 * A bookmark created here has its Readium `Locator` packed into the server's `cfi` field so
 * it round-trips; a bookmark made in a web reader comes back as a real EPUB CFI and is kept
 * in the list but jumps only to its chapter.
 */
@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao,
    private val api: BookmarkApi,
    private val serverRepository: ServerRepository,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    fun observe(serverId: String, bookId: String): Flow<List<Bookmark>> =
        dao.observeForBook(serverId, bookId).map { list -> list.map { it.toDomain() } }

    suspend fun add(
        serverId: String,
        bookId: String,
        locatorJson: String,
        progression: Double,
        title: String,
    ): Bookmark = withContext(io) {
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            serverId = serverId,
            bookId = bookId,
            locatorJson = locatorJson,
            progression = progression,
            title = title.ifBlank { "Bookmark" },
            createdAt = System.currentTimeMillis(),
            dirty = true,
        )
        dao.upsert(BookmarkEntity.fromDomain(bookmark))
        runCatching { push(serverId) }
        bookmark
    }

    suspend fun delete(id: String) = withContext(io) {
        val existing = dao.find(id) ?: return@withContext
        if (existing.remoteId == null) {
            dao.hardDelete(id)
        } else {
            dao.markDeleted(id)
            runCatching { push(existing.serverId) }
        }
    }

    /** Push local changes, then pull the server's bookmarks for [bookId] and merge them in. */
    suspend fun syncFromServer(serverId: String, bookId: String) = withContext(io) {
        val server = serverRepository.get(serverId) ?: return@withContext
        push(serverId)

        val remote = runCatching { api.list(server.listUrl(bookId)) }.getOrNull() ?: return@withContext
        val local = dao.forBook(serverId, bookId)
        val knownRemoteIds = local.mapNotNull { it.remoteId }.toSet()

        remote.forEach { dto ->
            val remoteId = dto.id?.toString() ?: return@forEach
            if (remoteId in knownRemoteIds) return@forEach

            // A bookmark we pushed but couldn't record the id for: adopt it by matching CFI.
            val ours = local.firstOrNull { it.remoteId == null && packCfi(it.locatorJson, it.progression) == dto.cfi }
            if (ours != null) {
                dao.upsert(ours.copy(remoteId = remoteId, dirty = false))
                return@forEach
            }

            val (loc, prog) = unpackCfi(dto.cfi)
            dao.upsert(
                BookmarkEntity(
                    id = UUID.randomUUID().toString(),
                    serverId = serverId,
                    bookId = bookId,
                    locatorJson = loc ?: "{}",
                    progression = prog,
                    title = dto.title?.takeIf { it.isNotBlank() } ?: "Bookmark",
                    // A real EPUB CFI we didn't write → chapter-only, kept for reference.
                    foreignCfi = if (loc == null) dto.cfi else null,
                    createdAt = System.currentTimeMillis(),
                    remoteId = remoteId,
                    dirty = false,
                ),
            )
        }
    }

    private suspend fun push(serverId: String) {
        val server = serverRepository.get(serverId) ?: return
        dao.pending().filter { it.serverId == serverId }.forEach { e ->
            val remoteId = e.remoteId
            runCatching {
                when {
                    e.deleted && remoteId != null -> {
                        api.delete(server.deleteUrl(e.bookId, remoteId))
                        dao.hardDelete(e.id)
                    }
                    e.deleted -> dao.hardDelete(e.id)
                    remoteId == null -> {
                        val bookIdNum = e.bookId.toLongOrNull() ?: return@forEach
                        val cfi = packCfi(e.locatorJson, e.progression)
                        val created = createRemote(server, bookIdNum, cfi, e.title)
                        if (created != null) dao.upsert(e.copy(remoteId = created, dirty = false))
                    }
                    else -> dao.upsert(e.copy(dirty = false))
                }
            }
        }
    }

    /** Creates the bookmark on the server; returns its id, or null to retry later. */
    private suspend fun createRemote(server: Server, bookId: Long, cfi: String, title: String): String? {
        return when (server.type) {
            ServerType.BOOKORBIT ->
                api.createBookOrbit(
                    server.resolve("/api/v1/books/$bookId/bookmarks"),
                    BookOrbitBookmarkBody(cfi = cfi, title = title),
                ).id?.toString()

            else -> try {
                api.createGrimmory(
                    server.resolve("/api/v1/bookmarks"),
                    GrimmoryBookmarkBody(bookId = bookId, cfi = cfi, title = title),
                ).id?.toString()
            } catch (e: ResponseException) {
                // 409 = this cfi is already bookmarked server-side; recover its id.
                if (e.response.status.value != 409) throw e
                runCatching {
                    api.list(server.resolve("/api/v1/bookmarks/book/$bookId"))
                        .firstOrNull { it.cfi == cfi }?.id?.toString()
                }.getOrNull()
            }
        }
    }

    private fun Server.listUrl(bookId: String): String = when (type) {
        ServerType.BOOKORBIT -> resolve("/api/v1/books/$bookId/bookmarks")
        else -> resolve("/api/v1/bookmarks/book/$bookId")
    }

    private fun Server.deleteUrl(bookId: String, remoteId: String): String = when (type) {
        ServerType.BOOKORBIT -> resolve("/api/v1/books/$bookId/bookmarks/$remoteId")
        else -> resolve("/api/v1/bookmarks/$remoteId")
    }

    private fun packCfi(locatorJson: String, progression: Double): String =
        JSONObject().put("l", JSONObject(locatorJson)).put("p", progression).toString()

    private fun unpackCfi(cfi: String?): Pair<String?, Double> {
        if (cfi.isNullOrBlank()) return null to 0.0
        return runCatching {
            val obj = JSONObject(cfi)
            obj.getJSONObject("l").toString() to obj.optDouble("p", 0.0)
        }.getOrDefault(null to 0.0)
    }
}
