package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.htmlToPlainText
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.AudiobookInfo
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.Chapter
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBook
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.BookOrbitPagination
import net.dexxicon.reader.core.serverapi.browse.BookOrbitQuery
import net.dexxicon.reader.core.serverapi.browse.BookOrbitSort
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Browses BookOrbit via its native REST API (JWT bearer) — no OPDS user required.
 * The book list comes from `POST /api/v1/books/query` (whole catalogue) or
 * `POST /api/v1/libraries/{id}/books` (one library); both share the request/response shape.
 */
class BookOrbitCatalogSource @Inject constructor(
    private val api: BookOrbitBrowseApi,
) : CatalogSource {

    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        api.libraries(server.resolve("/api/v1/libraries"))
            .map { CatalogShelf(id = it.id.toString(), title = it.name, bookCount = it.bookCount) }
    }

    override suspend fun books(
        server: Server,
        shelfId: String?,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
    ): Outcome<BookPage> = call {
        val url = when (shelfId) {
            null -> server.resolve("/api/v1/books/query")
            else -> server.resolve("/api/v1/libraries/$shelfId/books")
        }
        val response = api.booksQuery(
            url = url,
            body = BookOrbitQuery(
                sort = sortModel(sort),
                q = query?.trim()?.takeIf { it.isNotEmpty() },
                pagination = BookOrbitPagination(page = page, size = pageSize),
            ),
        )
        BookPage(
            books = response.items.map { it.toSummary(server) },
            page = response.page,
            hasMore = (response.page + 1) * pageSize < response.total,
            total = response.total,
        )
    }

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = call {
        val book = api.book(server.resolve("/api/v1/books/$bookId"))
        val summary = book.toSummary(server)
        val file = book.primaryFile
        BookDetail(
            summary = summary,
            description = book.description?.htmlToPlainText()?.takeIf { it.isNotBlank() },
            publisher = book.publisher,
            publishedDate = book.publishedDate ?: book.publishedYear?.toString(),
            language = book.language,
            isbn = book.isbn13 ?: book.isbn10,
            pageCount = book.pageCount,
            narrators = book.narrators,
            categories = book.genres,
            readingStatus = net.dexxicon.reader.core.model.ReadingStatus.fromServer(book.readStatus?.status),
            fileExtension = fileExtensionOf(file?.format, file?.filename),
            fileSizeBytes = file?.sizeBytes,
            audio = book.audioMetadata?.takeIf { summary.format == ContentFormat.AUDIOBOOK }?.let { am ->
                AudiobookInfo(
                    durationMs = (am.durationSeconds ?: file?.durationSeconds ?: 0L) * 1000L,
                    chapters = am.chapters.mapNotNull { c ->
                        c.title?.let { Chapter(it, c.startMs ?: 0L) }
                    },
                )
            },
            acquisitions = listOfNotNull(
                file?.let {
                    Acquisition(
                        href = server.resolve("/api/v1/books/files/${it.id}/serve"),
                        mediaType = summary.format.name,
                        format = summary.format,
                        relation = AcquisitionRelation.ACQUIRE,
                        sizeBytes = it.sizeBytes,
                    )
                },
            ),
        )
    }

    private fun BookOrbitBook.toSummary(server: Server) = BookSummary(
        id = id.toString(),
        serverId = server.id,
        title = title ?: "Untitled",
        authors = authors,
        series = seriesName,
        seriesIndex = seriesIndex?.toDoubleOrNull(),
        // Detail responses omit `hasCover`; the endpoint 404s cleanly when there is none.
        coverUrl = server.resolve("/api/v1/books/$id/cover"),
        format = formatOf(primaryFile?.format),
        shelfId = libraryId?.toString(),
    )

    private fun sortModel(sort: BookSort): List<BookOrbitSort> = when (sort) {
        BookSort.RECENT -> listOf(BookOrbitSort("addedAt", "desc"))
        BookSort.TITLE -> listOf(BookOrbitSort("title", "asc"))
        BookSort.SERIES -> listOf(BookOrbitSort("seriesName", "asc"))
    }

    /** Best-effort file extension: the server's `format` field, else the filename's suffix. */
    private fun fileExtensionOf(format: String?, filename: String?): String? {
        format?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { return it }
        return filename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
    }

    private fun formatOf(format: String?): ContentFormat = when (format?.lowercase()) {
        "epub", "kepub" -> ContentFormat.EPUB
        "pdf" -> ContentFormat.PDF
        "cbz", "cbr", "cb7" -> ContentFormat.COMIC
        "m4b", "mp3", "m4a", "opus", "ogg", "flac", "aac" -> ContentFormat.AUDIOBOOK
        "mobi", "prc" -> ContentFormat.MOBI
        "azw3", "azw" -> ContentFormat.AZW3
        "fb2" -> ContentFormat.FB2
        else -> ContentFormat.UNKNOWN
    }

    private suspend inline fun <T> call(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (e: HttpException) {
        if (e.code() == 401 || e.code() == 403) {
            Outcome.Failure(DexxiconError.Unauthorized("Session expired — reopen the server"))
        } else {
            Outcome.Failure(DexxiconError.Network("Server returned HTTP ${e.code()}"))
        }
    } catch (e: IOException) {
        Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
    } catch (e: Exception) {
        Outcome.Failure(DexxiconError.Unknown(e.message, e))
    }
}
