package net.dexxicon.reader.core.data.sync

import net.dexxicon.reader.core.data.catalog.KavitaCatalogSource
import kotlinx.serialization.json.buildJsonObject
import net.dexxicon.reader.core.data.catalog.KomgaCatalogSource
import net.dexxicon.reader.core.serverapi.browse.KomgaBrowseApi
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Logger
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import io.ktor.http.URLBuilder
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBook
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi

/**
 * Pulls a server's own "continue reading" / "continue listening" lists — the ones its web
 * app shows — so the Library shelves fill in as soon as a server is added, without the user
 * having to open every book in this app first.
 *
 * Fetch + map only; persistence (and "don't clobber local progress") lives in
 * [ReadingProgressRepository.seedFromServer].
 *
 * Phase 4 restructure (issue #126) — moved to commonMain; see [NativeProgressSync]'s doc
 * comment for the `@Inject`/`@Singleton` -> `:app`-hosted `@Provides` reasoning.
 */
class LibrarySeeder(
    private val grimmory: GrimmoryBrowseApi,
    private val bookOrbit: BookOrbitBrowseApi,
    private val komga: KomgaBrowseApi,
    /** issue #328 — Kavita's in-progress chapters come from its catalog source (shared caches). */
    private val kavita: KavitaCatalogSource,
    private val io: CoroutineDispatcher,
) {
    /** Every in-progress book the server knows about, as progress rows ready to persist. */
    suspend fun inProgress(server: Server): List<ReadingProgress> =
        when (val result = inProgressResult(server)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> {
                Logger.w(TAG, "seed ${server.type} ${server.displayName} failed: ${result.error.message}")
                emptyList()
            }
        }

    /** Like [inProgress] but reports *why* it failed instead of swallowing it. */
    suspend fun inProgressResult(server: Server): Outcome<List<ReadingProgress>> = withContext(io) {
        try {
            Outcome.Success(
                when (server.type) {
                    ServerType.GRIMMORY -> grimmoryRows(server)
                    ServerType.BOOKORBIT -> bookOrbitRows(server)
                    ServerType.KOMGA -> komgaRows(server)
                    ServerType.KAVITA -> kavitaRows(server)
                    else -> emptyList()
                },
            )
        } catch (e: ResponseException) {
            val status = e.response.status.value
            Logger.w(TAG, "continue lists ${server.type} ${server.displayName}: HTTP $status")
            if (status == 401 || status == 403) {
                Outcome.Failure(DexxiconError.Unauthorized("HTTP $status"))
            } else {
                Outcome.Failure(DexxiconError.Unknown("HTTP $status", e))
            }
        } catch (e: IOException) {
            Logger.w(TAG, "continue lists ${server.type} ${server.displayName}: ${e.message}")
            Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
        } catch (e: Exception) {
            // issue #283 — this surfaced only as Home's "didn't respond properly" banner, with
            // nothing in the log to say what the server actually sent.
            Logger.w(TAG, "continue lists ${server.type} ${server.displayName}: ${e::class.simpleName}: ${e.message}")
            Outcome.Failure(DexxiconError.Unknown(e.message, e))
        }
    }

    /**
     * issue #335 — what Grimmory's own web dashboard shows as "continue reading", through its
     * supported `/books/page` API: books in progress, most recently read first. Audiobooks
     * included, so one list covers Continue reading and Continue listening.
     */
    /** issue #327 — Komga's in-progress books, most recently read first. */
    private suspend fun komgaRows(server: Server): List<ReadingProgress> {
        val url = server.resolve("/api/v1/books/list?page=0&size=100&sort=readProgress.readDate,desc")
        val body = buildJsonObject { put("condition", KomgaCatalogSource.condition("readStatus", "IN_PROGRESS")) }
        return komga.bookList(url, body).content.map { book ->
            val pages = book.media.pagesCount
            ReadingProgress(
                serverId = server.id,
                bookId = book.id,
                percent = book.readProgress?.let { p ->
                    if (p.completed) 1.0 else if (pages > 0) (p.page.toDouble() / pages).coerceIn(0.0, 1.0) else null
                },
                title = book.title,
                author = book.metadata.authors.let { a ->
                    (a.filter { it.role.equals("writer", true) || it.role.equals("author", true) }.ifEmpty { a })
                        .map { it.name }.distinct().joinToString(", ").takeIf { it.isNotBlank() }
                },
                coverUrl = server.resolve("/api/v1/books/${book.id}/thumbnail"),
                format = KomgaCatalogSource.formatOf(book),
                digestUrl = server.resolve("/api/v1/books/${book.id}/file"),
                series = book.seriesTitle?.takeIf { it.isNotBlank() },
                seriesIndex = book.metadata.numberSort,
            )
        }
    }

    /** issue #328 — Kavita's chapters part-way through, in the series read most recently first. */
    private suspend fun kavitaRows(server: Server): List<ReadingProgress> =
        kavita.inProgress(server).map { (book, percent) ->
            ReadingProgress(
                serverId = server.id,
                bookId = book.id,
                percent = percent,
                title = book.title,
                author = book.authorLine.takeIf { it.isNotBlank() },
                coverUrl = book.coverUrl,
                format = book.format,
                digestUrl = server.resolve("/api/Download/chapter?chapterId=${book.id}"),
                series = book.series,
                seriesIndex = book.seriesIndex,
            )
        }

    private suspend fun grimmoryRows(server: Server): List<ReadingProgress> {
        val url = URLBuilder(server.resolve("/api/v1/books/page")).apply {
            parameters.append("page", "0")
            parameters.append("size", "100")
            parameters.append("sort", "-lastReadTime")
            listOf("READING", "RE_READING", "PAUSED").forEach { parameters.append("facet", "read_status:$it") }
            // Grimmory 3.5.0 ANDs several values of one facet unless told otherwise.
            parameters.append("facet_logic", "or")
        }.buildString()
        return grimmory.booksPage(url).content.distinctBy { it.id }.mapNotNull { it.toProgress(server) }
    }

    private fun GrimmoryBook.toProgress(server: Server): ReadingProgress? {
        val name = metadata.title?.takeIf { it.isNotBlank() } ?: return null
        val format = formatOf(primaryFile?.bookType ?: primaryFile?.extension)
        val coverPath = if (format == ContentFormat.AUDIOBOOK) {
            "/api/v1/media/book/$id/audiobook-cover"
        } else {
            "/api/v1/media/book/$id/cover"
        }
        return ReadingProgress(
            serverId = server.id,
            bookId = id.toString(),
            percent = readPercent?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            title = name,
            author = metadata.authors.joinToString(", ").takeIf { it.isNotBlank() },
            coverUrl = server.resolve(coverPath),
            format = format,
            digestUrl = server.resolve("/api/v1/books/$id/content"),
            series = metadata.seriesName?.takeIf { it.isNotBlank() },
            seriesIndex = metadata.seriesNumber,
        )
    }

    private suspend fun bookOrbitRows(server: Server): List<ReadingProgress> {
        val rows = buildList {
            addAll(bookOrbit.dashboardScroller(server.resolve("/api/v1/dashboard/scrollers/continue-reading?limit=50")))
            addAll(bookOrbit.dashboardScroller(server.resolve("/api/v1/dashboard/scrollers/continue-listening?limit=50")))
        }
        return rows.distinctBy { it.id }.mapNotNull { card ->
            val name = card.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = card.files.firstOrNull { it.role.equals("primary", ignoreCase = true) }
                ?: card.files.firstOrNull()
            val format = formatOf(file?.format)
            // BookCard.readingProgress is the file's percentage on a 0–100 scale, same as
            // /books/files/{id}/progress.
            val pct = card.readingProgress?.let { (it / 100.0).coerceIn(0.0, 1.0) }
            ReadingProgress(
                serverId = server.id,
                bookId = card.id.toString(),
                percent = pct,
                title = name,
                author = card.authors.joinToString(", ").takeIf { it.isNotBlank() },
                coverUrl = server.resolve("/api/v1/books/${card.id}/cover"),
                format = format,
                digestUrl = file?.let { server.resolve("/api/v1/books/files/${it.id}/serve") },
                series = card.seriesName?.takeIf { it.isNotBlank() },
                seriesIndex = card.seriesIndex?.toDoubleOrNull(),
            )
        }
    }

    private fun formatOf(raw: String?): ContentFormat = when (raw?.lowercase()) {
        "epub", "kepub" -> ContentFormat.EPUB
        "pdf" -> ContentFormat.PDF
        "cbx", "cbz", "cbr" -> ContentFormat.COMIC // cb7 deliberately excluded — issue #110
        "audiobook", "m4b", "mp3", "m4a", "opus", "ogg", "flac", "aac" -> ContentFormat.AUDIOBOOK
        "mobi", "prc" -> ContentFormat.MOBI
        "azw3", "azw" -> ContentFormat.AZW3
        "fb2" -> ContentFormat.FB2
        else -> ContentFormat.UNKNOWN
    }

    private companion object {
        const val TAG = "LibrarySeeder"
    }
}
