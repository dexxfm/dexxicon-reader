package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.htmlToPlainText
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.AudiobookInfo
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookGroupPage
import net.dexxicon.reader.core.model.Chapter
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.browse.GrimmoryAppPage
import net.dexxicon.reader.core.serverapi.browse.GrimmoryAppSummary
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBook
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import io.ktor.client.plugins.ResponseException
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.io.IOException
import kotlin.math.roundToInt

/** Browses Grimmory / BookLore via its native REST API (uses the JWT bearer). */
class GrimmoryCatalogSource(
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
        formats: Set<ContentFormat>?,
        facetHref: String?,
    ): Outcome<BookPage> = call {
        // Ktor's URLBuilder percent-encodes each parameter for us — java.net.URLEncoder
        // (the original androidMain version's choice) is JVM-only, unreachable from iOS.
        val url = URLBuilder(server.resolve("/api/v1/books/page")).apply {
            parameters.append("page", page.toString())
            parameters.append("size", pageSize.toString())
            parameters.append("sort", sortKey(sort))
            if (!query.isNullOrBlank()) parameters.append("query", query.trim())
            if (shelfId != null) parameters.append("facet", "file_type:$shelfId")
        }.buildString()
        val response = api.booksPage(url)
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

    // issues #253/#254/#256 — the /api/v1/app API (Grimmory's own mobile API) is the one with
    // library/shelf/series filters; /api/v1/books/page above has none (see [shelves]).
    override suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> = call {
        fun group(id: Long, name: String, count: Int?) =
            BookGroup(server.id, kind, id.toString(), name, count, serverName = server.displayName)
        when (kind) {
            BookGroupKind.LIBRARY -> api.appLibraries(server.resolve("/api/v1/app/libraries"))
                .map { group(it.id, it.name, it.bookCount) }
            BookGroupKind.COLLECTION -> api.appShelves(server.resolve("/api/v1/app/shelves"))
                .map { group(it.id, it.name, it.bookCount) }
            BookGroupKind.SMART -> api.appMagicShelves(server.resolve("/api/v1/app/shelves/magic"))
                .map { group(it.id, it.name, it.bookCount) }
            BookGroupKind.SERIES -> emptyList()
        }
    }

    override suspend fun series(
        server: Server,
        query: String?,
        page: Int,
        pageSize: Int,
    ): Outcome<BookGroupPage> = call {
        val url = URLBuilder(server.resolve("/api/v1/app/series")).apply {
            parameters.append("page", page.toString())
            parameters.append("size", pageSize.coerceAtMost(MAX_APP_PAGE).toString())
            parameters.append("sort", "name")
            parameters.append("dir", "asc")
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters.append("search", it) }
        }.buildString()
        val response = api.appSeries(url)
        BookGroupPage(
            groups = response.content.map { s ->
                val cover = s.coverBooks.firstOrNull()
                BookGroup(
                    serverId = server.id,
                    kind = BookGroupKind.SERIES,
                    // Grimmory keys series by name — there's no series id to use instead.
                    id = s.seriesName,
                    name = s.seriesName,
                    bookCount = s.bookCount,
                    coverUrl = cover?.let { server.resolve(coverPath(it.bookId, formatOf(it.primaryFileType, null))) },
                    serverName = server.displayName,
                    authors = s.authors,
                )
            },
            hasMore = response.hasMoreAfter(page),
        )
    }

    override suspend fun groupBooks(
        server: Server,
        group: BookGroup,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
        formats: Set<ContentFormat>?,
        facetHref: String?,
    ): Outcome<BookPage> = call {
        val size = pageSize.coerceAtMost(MAX_APP_PAGE)
        val url = if (group.kind == BookGroupKind.SERIES) {
            URLBuilder(server.resolve("/api/v1/app/series")).apply {
                appendPathSegments(group.id, "books")
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
                parameters.append("sort", "seriesNumber")
                parameters.append("dir", "asc")
            }.buildString()
        } else {
            URLBuilder(server.resolve("/api/v1/app/books")).apply {
                parameters.append("page", page.toString())
                parameters.append("size", size.toString())
                val (field, dir) = appSort(sort)
                parameters.append("sort", field)
                parameters.append("dir", dir)
                query?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters.append("search", it) }
                val filter = when (group.kind) {
                    BookGroupKind.LIBRARY -> "libraryId"
                    BookGroupKind.COLLECTION -> "shelfId"
                    else -> "magicShelfId"
                }
                parameters.append(filter, group.id)
            }.buildString()
        }
        val response = api.appBooks(url)
        BookPage(
            books = response.content.map { it.toSummary(server) },
            page = page,
            hasMore = response.hasMoreAfter(page),
            total = response.totalElements?.toInt(),
        )
    }

    private fun GrimmoryAppPage<*>.hasMoreAfter(page: Int): Boolean =
        hasNext ?: totalPages?.let { page + 1 < it } ?: false

    private fun GrimmoryAppSummary.toSummary(server: Server): BookSummary {
        val format = formatOf(primaryFileType, null)
        return BookSummary(
            id = id.toString(),
            serverId = server.id,
            title = title ?: "Untitled",
            authors = authors,
            series = seriesName,
            seriesIndex = seriesNumber,
            coverUrl = server.resolve(coverPath(id, format)),
            format = format,
        )
    }

    /** Audiobooks are served from a separate cover route; the generic one 404s. */
    private fun coverPath(bookId: Long, format: ContentFormat): String =
        if (format == ContentFormat.AUDIOBOOK) {
            "/api/v1/media/book/$bookId/audiobook-cover"
        } else {
            "/api/v1/media/book/$bookId/cover"
        }

    /** The app API's single-key sort (`AppBookService.getSortField`). */
    private fun appSort(sort: BookSort): Pair<String, String> = when (sort) {
        BookSort.RECENT -> "addedOn" to "desc"
        BookSort.TITLE -> "title" to "asc"
        BookSort.SERIES -> "seriesName" to "asc"
    }

    override suspend fun wantToRead(server: Server): Outcome<List<BookSummary>> = call {
        // Grimmory has no "want to read"; the app stores that as UNREAD (see NativeProgressSync),
        // and books never touched carry no readStatus, so this facet is effectively the shelf.
        val url = URLBuilder(server.resolve("/api/v1/books/page")).apply {
            parameters.append("page", "0")
            parameters.append("size", "50")
            parameters.append("sort", "-addedOn")
            parameters.append("facet", "read_status:UNREAD")
        }.buildString()
        api.booksPage(url).content.map { it.toSummary(server) }
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
            readingStatus = net.dexxicon.reader.core.model.ReadingStatus.fromServer(book.readStatus),
            rating = book.personalRating?.roundToInt(),
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
            "cbz", "cbr" -> ContentFormat.COMIC // cb7 deliberately excluded — issue #110
            "m4b", "mp3", "m4a" -> ContentFormat.AUDIOBOOK
            "mobi", "prc" -> ContentFormat.MOBI
            "azw3", "azw" -> ContentFormat.AZW3
            "fb2" -> ContentFormat.FB2
            else -> ContentFormat.UNKNOWN
        }
    }

    private companion object {
        /** `AppBookService.MAX_PAGE_SIZE` — larger requests are silently clamped to it. */
        const val MAX_APP_PAGE = 50
    }

    private suspend inline fun <T> call(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (e: ResponseException) {
        val status = e.response.status.value
        if (status == 401 || status == 403) {
            Outcome.Failure(DexxiconError.Unauthorized("Session expired — reopen the server"))
        } else if (status == 404) {
            // issue #251 — "this book no longer exists" has to be told apart from "couldn't
            // reach the server", or a stale Continue entry can never safely be cleaned up.
            Outcome.Failure(DexxiconError.NotFound("That's no longer on the server"))
        } else {
            Outcome.Failure(DexxiconError.Network("Server returned HTTP $status"))
        }
    } catch (e: IOException) {
        Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
    } catch (e: Exception) {
        Outcome.Failure(DexxiconError.Unknown(e.message, e))
    }
}
