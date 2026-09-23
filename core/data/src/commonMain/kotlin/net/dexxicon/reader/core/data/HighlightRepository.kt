package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.dexxicon.reader.core.common.currentTimeMillis
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.entity.HighlightEntity
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.HighlightColor
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.annotation.AnnotationApi
import net.dexxicon.reader.core.serverapi.annotation.BookOrbitAnnotationDto
import net.dexxicon.reader.core.serverapi.annotation.CreateAnnotationDto
import net.dexxicon.reader.core.serverapi.annotation.UpdateAnnotationDto
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Highlights & notes. The source of truth is the local `highlights` table; a best-effort
 * sync mirrors them to the server's annotation API (BookLore/Grimmory have full CRUD;
 * BookOrbit is read-only). The Readium `Locator` for a highlight we created is packed into
 * the server's `cfi` field so it round-trips; foreign annotations (real EPUB CFIs from a
 * web reader) are kept for the list but can't be placed precisely.
 *
 * Phase 2 of the shared-reader-chrome redesign (issue #183) — moved to commonMain following
 * the same fix [net.dexxicon.reader.core.data.ReadingProgressRepository] already received
 * (issue #126); see [net.dexxicon.reader.core.data.BookmarkRepository]'s doc comment, which
 * got the identical treatment in the same pass.
 */
@OptIn(ExperimentalUuidApi::class)
class HighlightRepository(
    private val dao: HighlightDao,
    private val api: AnnotationApi,
    private val serverRepository: ServerRepository,
    private val io: CoroutineDispatcher,
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
        val now = currentTimeMillis()
        val highlight = Highlight(
            id = Uuid.random().toString(),
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
            existing.copy(note = note, dirty = true, updatedAt = currentTimeMillis()),
        )
        push(existing.serverId)
    }

    suspend fun updateColor(id: String, color: HighlightColor) = withContext(io) {
        val existing = dao.find(id) ?: return@withContext
        dao.upsert(
            existing.copy(color = color.name, dirty = true, updatedAt = currentTimeMillis()),
        )
        push(existing.serverId)
    }

    suspend fun delete(id: String) = withContext(io) {
        val existing = dao.find(id) ?: return@withContext
        if (existing.remoteId == null) {
            dao.hardDelete(id)
        } else {
            dao.markDeleted(id, currentTimeMillis())
            push(existing.serverId)
        }
    }

    /** Pull server annotations for a book and merge them in. */
    suspend fun syncFromServer(serverId: String, bookId: String) = withContext(io) {
        val server = serverRepository.get(serverId) ?: return@withContext
        push(serverId)
        val remote = runCatching {
            when (server.type) {
                ServerType.BOOKORBIT -> bookOrbitAnnotations(server, bookId).mapNotNull { dto ->
                    RemoteHighlight(
                        remoteId = dto.id ?: return@mapNotNull null,
                        text = dto.text.orEmpty(),
                        note = dto.note,
                        color = HighlightColor.fromHex(dto.color),
                        chapter = dto.chapterTitle,
                        locatorJson = null,
                        progression = 0.0,
                        // issue #266 — BookOrbit's positions are real EPUB CFIs, not Readium
                        // locators; kept so the reader can still jump to the right chapter.
                        cfi = dto.cfi?.takeIf { it.isNotBlank() },
                        pageNumber = dto.pageno,
                        createdAt = parseServerTime(dto.highlightedAt ?: dto.createdAt),
                    )
                }

                else ->
                    api.listForBook(server.resolve("/api/v1/annotations/book/$bookId")).mapNotNull { dto ->
                        val (loc, prog) = unpackCfi(dto.cfi)
                        RemoteHighlight(
                            remoteId = dto.id?.toString() ?: return@mapNotNull null,
                            text = dto.text.orEmpty(),
                            note = dto.note,
                            color = HighlightColor.fromHex(dto.color),
                            chapter = dto.chapterTitle,
                            locatorJson = loc,
                            progression = prog,
                            // A CFI written by BookLore's own web reader rather than this app's
                            // packed locator.
                            cfi = dto.cfi?.takeIf { loc == null && it.isNotBlank() },
                            pageNumber = null,
                            createdAt = parseServerTime(dto.createdAt),
                        )
                    }
            }
        }.getOrNull() ?: return@withContext

        val local = dao.forBook(serverId, bookId)
        val byRemoteId = local.filter { it.remoteId != null }.associateBy { it.remoteId }
        remote.forEach { r ->
            val existing = byRemoteId[r.remoteId]
            if (existing == null) {
                val now = currentTimeMillis()
                dao.upsert(
                    HighlightEntity(
                        id = Uuid.random().toString(),
                        serverId = serverId,
                        bookId = bookId,
                        locatorJson = r.locatorJson ?: "{}",
                        progression = r.progression,
                        text = r.text,
                        note = r.note,
                        color = r.color.name,
                        chapterTitle = r.chapter,
                        // issue #266 — when it was actually highlighted, not when this device
                        // first synced it (which is what "date highlighted" used to show).
                        createdAt = r.createdAt ?: now,
                        updatedAt = now,
                        remoteId = r.remoteId,
                        dirty = false,
                        cfi = r.cfi,
                        pageNumber = r.pageNumber,
                    ),
                )
            } else if (!existing.dirty) {
                // issue #266 — rows synced before these fields were kept pick them up now;
                // anything with an unpushed local edit is left alone.
                val backfilled = existing.copy(
                    createdAt = r.createdAt ?: existing.createdAt,
                    cfi = existing.cfi ?: r.cfi,
                    pageNumber = existing.pageNumber ?: r.pageNumber,
                    chapterTitle = existing.chapterTitle ?: r.chapter,
                )
                if (backfilled != existing) dao.upsert(backfilled)
            }
        }
    }

    /** issue #266 — every page of a book's BookOrbit annotations (EPUB highlights only: PDF
     *  ones carry no text position this app can use). The first page alone used to be all
     *  that was ever fetched. */
    private suspend fun bookOrbitAnnotations(server: Server, bookId: String): List<BookOrbitAnnotationDto> {
        val all = mutableListOf<BookOrbitAnnotationDto>()
        var page = 1
        while (page <= MAX_ANNOTATION_PAGES) {
            val response = api.listBookOrbit(
                server.resolve("/api/v1/annotations?bookId=$bookId&page=$page&pageSize=$ANNOTATION_PAGE_SIZE&sortBy=position&sortDir=asc"),
            )
            all += response.items
            val total = response.total ?: break
            if (response.items.isEmpty() || all.size >= total) break
            page++
        }
        return all
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
        buildJsonObject {
            put("l", Json.parseToJsonElement(locatorJson))
            put("p", progression)
        }.toString()

    private fun unpackCfi(cfi: String?): Pair<String?, Double> {
        if (cfi.isNullOrBlank()) return null to 0.0
        return runCatching {
            val obj = Json.parseToJsonElement(cfi).jsonObject
            val locator = obj["l"]?.jsonObject?.toString()
            val progression = obj["p"]?.jsonPrimitive?.double ?: 0.0
            locator to progression
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
        val cfi: String?,
        val pageNumber: Int?,
        val createdAt: Long?,
    )

    private companion object {
        const val ANNOTATION_PAGE_SIZE = 100
        /** Safety stop — 5,000 highlights in one book is well past any real reader. */
        const val MAX_ANNOTATION_PAGES = 50
    }
}

/**
 * issue #266 — a server timestamp as epoch millis. BookOrbit sends ISO-8601 instants
 * (`2026-09-20T14:03:11.000Z`); Grimmory sends a zoneless `LocalDateTime`
 * (`2026-09-20T14:03:11`), read as UTC. Null for anything unparseable.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
internal fun parseServerTime(value: String?): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hasZone = raw.endsWith("Z", ignoreCase = true) || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(raw.substringAfter('T', ""))
    return runCatching { kotlin.time.Instant.parse(if (hasZone) raw else raw + "Z").toEpochMilliseconds() }.getOrNull()
}
