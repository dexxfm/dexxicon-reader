package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.entity.HighlightEntity
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.HighlightColor
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.annotation.AnnotationApi
import net.dexxicon.reader.core.serverapi.annotation.CreateAnnotationDto
import net.dexxicon.reader.core.serverapi.annotation.UpdateAnnotationDto
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Highlights & notes. The source of truth is the local `highlights` table; a best-effort
 * sync mirrors them to the server's annotation API (BookLore/Grimmory have full CRUD;
 * BookOrbit is read-only). The Readium `Locator` for a highlight we created is packed into
 * the server's `cfi` field so it round-trips; foreign annotations (real EPUB CFIs from a
 * web reader) are kept for the list but can't be placed precisely.
 */
@Singleton
class HighlightRepository @Inject constructor(
    private val dao: HighlightDao,
    private val api: AnnotationApi,
    private val serverRepository: ServerRepository,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    fun observe(serverId: String, bookId: String): Flow<List<Highlight>> =
        dao.observeForBook(serverId, bookId).map { list -> list.map { it.toDomain() } }

    suspend fun forBook(serverId: String, bookId: String): List<Highlight> = withContext(io) {
        dao.forBook(serverId, bookId).map { it.toDomain() }
    }

    suspend fun add(
        serverId: String,
        bookId: String,
        locatorJson: String,
        progression: Double,
        text: String,
        color: HighlightColor,
        chapterTitle: String?,
    ): Highlight = withContext(io) {
        val now = System.currentTimeMillis()
        val highlight = Highlight(
            id = UUID.randomUUID().toString(),
            serverId = serverId,
            bookId = bookId,
            locatorJson = locatorJson,
            progression = progression,
            text = text,
            color = color,
            chapterTitle = chapterTitle,
            createdAt = now,
            updatedAt = now,
            dirty = true,
        )
        dao.upsert(HighlightEntity.fromDomain(highlight))
        push(serverId)
        highlight
    }

    suspend fun updateNote(id: String, note: String?) = withContext(io) {
        val existing = dao.find(id) ?: return@withContext
        dao.upsert(
            existing.copy(note = note, dirty = true, updatedAt = System.currentTimeMillis()),
        )
        push(existing.serverId)
    }

    suspend fun updateColor(id: String, color: HighlightColor) = withContext(io) {
        val existing = dao.find(id) ?: return@withContext
        dao.upsert(
            existing.copy(color = color.name, dirty = true, updatedAt = System.currentTimeMillis()),
        )
        push(existing.serverId)
    }

    suspend fun delete(id: String) = withContext(io) {
        val existing = dao.find(id) ?: return@withContext
        if (existing.remoteId == null) {
            dao.hardDelete(id)
        } else {
            dao.markDeleted(id, System.currentTimeMillis())
            push(existing.serverId)
        }
    }

    /** Pull server annotations for a book and merge them in. */
    suspend fun syncFromServer(serverId: String, bookId: String) = withContext(io) {
        val server = serverRepository.get(serverId) ?: return@withContext
        push(serverId)
        val remote = runCatching {
            when (server.type) {
                ServerType.BOOKORBIT ->
                    api.listBookOrbit(server.resolve("/api/v1/annotations?bookId=$bookId")).items
                        .map { dto ->
                            RemoteHighlight(
                                remoteId = dto.id ?: return@map null,
                                text = dto.text.orEmpty(),
                                note = dto.note,
                                color = HighlightColor.fromHex(dto.color),
                                chapter = dto.chapterTitle,
                                locatorJson = null,
                                progression = 0.0,
                            )
                        }.filterNotNull()

                else ->
                    api.listForBook(server.resolve("/api/v1/annotations/book/$bookId")).map { dto ->
                        val (loc, prog) = unpackCfi(dto.cfi)
                        RemoteHighlight(
                            remoteId = dto.id?.toString() ?: return@map null,
                            text = dto.text.orEmpty(),
                            note = dto.note,
                            color = HighlightColor.fromHex(dto.color),
                            chapter = dto.chapterTitle,
                            locatorJson = loc,
                            progression = prog,
                        )
                    }.filterNotNull()
            }
        }.getOrNull() ?: return@withContext

        val local = dao.forBook(serverId, bookId)
        val knownRemoteIds = local.mapNotNull { it.remoteId }.toSet()
        remote.filter { it.remoteId !in knownRemoteIds }.forEach { r ->
            val now = System.currentTimeMillis()
            dao.upsert(
                HighlightEntity(
                    id = UUID.randomUUID().toString(),
                    serverId = serverId,
                    bookId = bookId,
                    locatorJson = r.locatorJson ?: "{}",
                    progression = r.progression,
                    text = r.text,
                    note = r.note,
                    color = r.color.name,
                    chapterTitle = r.chapter,
                    createdAt = now,
                    updatedAt = now,
                    remoteId = r.remoteId,
                    dirty = false,
                ),
            )
        }
    }

    private suspend fun push(serverId: String) {
        val server = serverRepository.get(serverId) ?: return
        if (server.type == ServerType.BOOKORBIT) return // read-only
        val bookIdNum = { s: String -> s.toLongOrNull() }
        dao.pending().filter { it.serverId == serverId }.forEach { e ->
            runCatching {
                when {
                    e.deleted && e.remoteId != null -> {
                        api.delete(server.resolve("/api/v1/annotations/${e.remoteId}"))
                        dao.hardDelete(e.id)
                    }
                    e.remoteId == null -> {
                        val bookId = bookIdNum(e.bookId) ?: return@forEach
                        val created = api.create(
                            server.resolve("/api/v1/annotations"),
                            CreateAnnotationDto(
                                bookId = bookId,
                                cfi = packCfi(e.locatorJson, e.progression),
                                text = e.text,
                                color = HighlightColor.valueOf(e.color).serverHex,
                                note = e.note,
                                chapterTitle = e.chapterTitle,
                            ),
                        )
                        dao.upsert(e.copy(remoteId = created.id?.toString(), dirty = false))
                    }
                    else -> {
                        api.update(
                            server.resolve("/api/v1/annotations/${e.remoteId}"),
                            UpdateAnnotationDto(
                                color = HighlightColor.valueOf(e.color).serverHex,
                                note = e.note,
                            ),
                        )
                        dao.upsert(e.copy(dirty = false))
                    }
                }
            }
        }
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

    private data class RemoteHighlight(
        val remoteId: String,
        val text: String,
        val note: String?,
        val color: HighlightColor,
        val chapter: String?,
        val locatorJson: String?,
        val progression: Double,
    )
}
