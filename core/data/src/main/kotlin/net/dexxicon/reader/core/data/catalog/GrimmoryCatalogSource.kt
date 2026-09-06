package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBook
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** Browses Grimmory / BookLore via its native REST API (uses the JWT bearer). */
class GrimmoryCatalogSource @Inject constructor(
    private val api: GrimmoryBrowseApi,
) : CatalogSource {

    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        api.libraries(server.resolve("/api/v1/libraries"))
            .map { CatalogShelf(id = it.id.toString(), title = it.name) }
    }

    override suspend fun books(
        server: Server,
        shelfId: String?,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
    ): Outcome<BookPage> = call {
        val params = buildList {
            add("page=$page")
            add("size=$pageSize")
            add("sort=${sortKey(sort)}")
            if (!query.isNullOrBlank()) {
                add("query=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"))
            }
        }.joinToString("&")
        val response = api.booksPage(server.resolve("/api/v1/books/page?$params"))
        val books = response.content
            .filter { shelfId == null || it.libraryId?.toString() == shelfId }
            .map { it.toSummary(server) }
        val totalPages = response.effectiveTotalPages
        BookPage(
            books = books,
            page = page,
            hasMore = when {
                totalPages != null -> response.effectiveNumber + 1 < totalPages
                else -> response.content.size >= pageSize
            },
            total = response.effectiveTotal?.toInt(),
        )
    }

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = call {
        val book = api.book(server.resolve("/api/v1/books/$bookId"))
        val summary = book.toSummary(server)
        BookDetail(
            summary = summary,
            description = book.metadata.description,
            publisher = book.metadata.publisher,
            publishedDate = book.metadata.publishedDate,
            language = book.metadata.language,
            isbn = book.metadata.isbn13 ?: book.metadata.isbn10,
            pageCount = book.metadata.pageCount,
            categories = book.metadata.categories,
            fileSizeBytes = book.primaryFile?.fileSizeKb?.let { it * 1024 },
            acquisitions = listOf(
                Acquisition(
                    href = server.resolve("/api/v1/books/$bookId/content"),
                    mediaType = summary.format.name,
                    format = summary.format,
                    relation = AcquisitionRelation.ACQUIRE,
                    sizeBytes = book.primaryFile?.fileSizeKb?.let { it * 1024 },
                ),
            ),
        )
    }

    private fun GrimmoryBook.toSummary(server: Server) = BookSummary(
        id = id.toString(),
        serverId = server.id,
        title = metadata.title ?: "Untitled",
        authors = metadata.authors,
        series = metadata.seriesName,
        seriesIndex = metadata.seriesNumber,
        coverUrl = server.resolve("/api/v1/media/book/$id/cover"),
        format = formatOf(primaryFile?.bookType, primaryFile?.extension),
        shelfId = libraryId?.toString(),
    )

    // BookLore sort: comma-separated keys, '-' prefix = descending.
    private fun sortKey(sort: BookSort): String = when (sort) {
        BookSort.RECENT -> "-addedOn"
        BookSort.TITLE -> "title"
        BookSort.SERIES -> "seriesName,seriesNumber"
    }

    private fun formatOf(bookType: String?, extension: String?): ContentFormat = when {
        bookType.equals("EPUB", true) -> ContentFormat.EPUB
        bookType.equals("PDF", true) -> ContentFormat.PDF
        bookType.equals("CBX", true) -> ContentFormat.COMIC
        bookType.equals("AUDIOBOOK", true) -> ContentFormat.AUDIOBOOK
        bookType.equals("MOBI", true) -> ContentFormat.MOBI
        bookType.equals("AZW3", true) || bookType.equals("AZW", true) -> ContentFormat.AZW3
        bookType.equals("FB2", true) -> ContentFormat.FB2
        else -> when (extension?.lowercase()) {
            "epub", "kepub" -> ContentFormat.EPUB
            "pdf" -> ContentFormat.PDF
            "cbz", "cbr", "cb7" -> ContentFormat.COMIC
            "m4b", "mp3", "m4a" -> ContentFormat.AUDIOBOOK
            "mobi", "prc" -> ContentFormat.MOBI
            "azw3", "azw" -> ContentFormat.AZW3
            "fb2" -> ContentFormat.FB2
            else -> ContentFormat.UNKNOWN
        }
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
