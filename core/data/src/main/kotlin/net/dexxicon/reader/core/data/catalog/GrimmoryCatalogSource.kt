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
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBook
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** Browses Grimmory / BookLore via its native REST API (uses the JWT bearer). */
class GrimmoryCatalogSource @Inject constructor(
    private val api: GrimmoryBrowseApi,
) : CatalogSource {

    // BookLore has no library filter on /books/page, but it does expose a `file_type` facet
    // (CBX/EPUB/AUDIOBOOK/PDF/MOBI) — which is the grouping that actually matters to a reader.
    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        val fileType = api.facets(server.resolve("/api/v1/books/facets"))
            .facets.firstOrNull { it.metadata.key == "file_type" }
            ?: return@call emptyList()
        fileType.links
            .mapNotNull { it.value?.let { v -> v to it.properties.numberOfItems } }
            .sortedByDescending { it.second ?: 0 }
            .map { (value, count) ->
                CatalogShelf(id = value, title = shelfTitle(value), bookCount = count)
            }
    }

    private fun shelfTitle(fileType: String): String = when (fileType.uppercase()) {
        "CBX" -> "Comics"
        "EPUB" -> "Books"
        "AUDIOBOOK" -> "Audiobooks"
        "PDF" -> "PDF"
        "MOBI", "AZW3", "AZW" -> "Kindle"
        "FB2" -> "FB2"
        else -> fileType.lowercase().replaceFirstChar { it.uppercase() }
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
            if (shelfId != null) {
                add("facet=" + java.net.URLEncoder.encode("file_type:$shelfId", "UTF-8"))
            }
        }.joinToString("&")
        val response = api.booksPage(server.resolve("/api/v1/books/page?$params"))
        val books = response.content.map { it.toSummary(server) }
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
        // ?withDescription=true — BookLore omits the description from the default DTO.
        val book = api.book(server.resolve("/api/v1/books/$bookId?withDescription=true"))
        val summary = book.toSummary(server)
        val isAudio = summary.format == ContentFormat.AUDIOBOOK
        val audioMeta = book.metadata.audiobookMetadata
        val acquisitionHref = if (isAudio) {
            server.resolve("/api/v1/audiobooks/$bookId/stream")
        } else {
            server.resolve("/api/v1/books/$bookId/content")
        }
        BookDetail(
            summary = summary,
            description = book.metadata.description?.htmlToPlainText()?.takeIf { it.isNotBlank() },
            publisher = book.metadata.publisher,
            publishedDate = book.metadata.publishedDate,
            language = book.metadata.language,
            isbn = book.metadata.isbn13 ?: book.metadata.isbn10,
            pageCount = book.metadata.pageCount,
            narrators = listOfNotNull(book.metadata.narrator?.takeIf { it.isNotBlank() }),
            categories = book.metadata.categories,
            fileExtension = book.primaryFile?.let { f ->
                f.extension?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    ?: f.bookType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            },
            fileSizeBytes = book.primaryFile?.fileSizeKb?.let { it * 1024 },
            audio = if (isAudio && audioMeta != null) {
                AudiobookInfo(
                    durationMs = (audioMeta.durationSeconds ?: 0L) * 1000L,
                    chapters = audioMeta.chapters.mapNotNull { c ->
                        c.title?.let { Chapter(it, c.startTimeMs ?: 0L) }
                    },
                )
            } else {
                null
            },
            acquisitions = listOf(
                Acquisition(
                    href = acquisitionHref,
                    mediaType = summary.format.name,
                    format = summary.format,
                    relation = AcquisitionRelation.ACQUIRE,
                    sizeBytes = book.primaryFile?.fileSizeKb?.let { it * 1024 },
                ),
            ),
        )
    }

    private fun GrimmoryBook.toSummary(server: Server): BookSummary {
        val format = formatOf(primaryFile?.bookType, primaryFile?.extension)
        // Audiobooks are served from a separate cover route; the generic one 404s.
        val coverPath = if (format == ContentFormat.AUDIOBOOK) {
            "/api/v1/media/book/$id/audiobook-cover"
        } else {
            "/api/v1/media/book/$id/cover"
        }
        return BookSummary(
            id = id.toString(),
            serverId = server.id,
            title = metadata.title ?: "Untitled",
            authors = metadata.authors,
            series = metadata.seriesName,
            seriesIndex = metadata.seriesNumber,
            coverUrl = server.resolve(coverPath),
            format = format,
            shelfId = libraryId?.toString(),
        )
    }

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
