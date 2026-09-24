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
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBook
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.BookOrbitPagination
import net.dexxicon.reader.core.serverapi.browse.BookOrbitQuery
import net.dexxicon.reader.core.serverapi.browse.BookOrbitFilterRule
import net.dexxicon.reader.core.serverapi.browse.BookOrbitFilterGroup
import net.dexxicon.reader.core.serverapi.browse.BookOrbitSort
import io.ktor.client.plugins.ResponseException
import io.ktor.http.URLBuilder
import kotlinx.io.IOException

/**
 * Browses BookOrbit via its native REST API (JWT bearer) — no OPDS user required.
 * The book list comes from `POST /api/v1/books/query` (whole catalogue) or
 * `POST /api/v1/libraries/{id}/books` (one library); both share the request/response shape.
 */
class BookOrbitCatalogSource(
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
        formats: Set<ContentFormat>?,
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
                filter = formatFilter(formats),
            ),
        )
        BookPage(
            books = response.items.map { it.toSummary(server) },
            page = response.page,
            hasMore = (response.page + 1) * pageSize < response.total,
            total = response.total,
        )
    }

    override suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> = call {
        fun group(id: Long, name: String, count: Int?) =
            BookGroup(server.id, kind, id.toString(), name, count, serverName = server.displayName)
        when (kind) {
            BookGroupKind.LIBRARY -> api.libraries(server.resolve("/api/v1/libraries"))
                .map { group(it.id, it.name, it.bookCount) }
            // Podcast collections/scopes share these endpoints; only book ones belong here.
            BookGroupKind.COLLECTION -> api.collections(server.resolve("/api/v1/collections"))
                .filter { it.isBooks }
                .map { group(it.id, it.name, it.bookCount) }
            BookGroupKind.SMART -> api.smartScopes(server.resolve("/api/v1/smart-scopes"))
                .filter { it.isBooks }
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
        val url = URLBuilder(server.resolve("/api/v1/series")).apply {
            parameters.append("page", page.toString())
            parameters.append("size", pageSize.coerceAtMost(MAX_SERIES_PAGE).toString())
            parameters.append("sort", "name")
            parameters.append("order", "asc")
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters.append("q", it) }
        }.buildString()
        val response = api.series(url)
        BookGroupPage(
            groups = response.items.map { s ->
                BookGroup(
                    serverId = server.id,
                    kind = BookGroupKind.SERIES,
                    id = s.id.toString(),
                    name = s.name,
                    bookCount = s.bookCount,
                    coverUrl = s.coverBookIds.firstOrNull()?.let { server.resolve("/api/v1/books/$it/cover") },
                    serverName = server.displayName,
                    authors = s.authors,
                )
            },
            hasMore = (response.page + 1) * response.size.coerceAtLeast(1) < response.total,
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
    ): Outcome<BookPage> {
        val queryPath = when (group.kind) {
            BookGroupKind.LIBRARY -> return books(server, group.id, query, sort, page, pageSize, formats)
            BookGroupKind.COLLECTION -> "/api/v1/collections/${group.id}/books/query"
            BookGroupKind.SMART -> "/api/v1/smart-scopes/${group.id}/books/query"
            BookGroupKind.SERIES -> null
        }
        return call {
            val response = if (queryPath != null) {
                // Same BookQuery body/BooksPage response as /books/query — the server runs all
                // of them through one BookQueryPipe.
                api.booksQuery(
                    url = server.resolve(queryPath),
                    body = BookOrbitQuery(
                        sort = sortModel(sort),
                        q = query?.trim()?.takeIf { it.isNotEmpty() },
                        pagination = BookOrbitPagination(page = page, size = pageSize),
                        filter = formatFilter(formats),
                    ),
                )
            } else {
                api.booksPage(
                    URLBuilder(server.resolve("/api/v1/series/${group.id}/books")).apply {
                        parameters.append("page", page.toString())
                        parameters.append("size", pageSize.coerceAtMost(MAX_SERIES_PAGE).toString())
                        parameters.append("sort", "seriesIndex")
                        parameters.append("order", "asc")
                    }.buildString(),
                )
            }
            BookPage(
                books = response.items.map { it.toSummary(server) },
                page = response.page,
                hasMore = (response.page + 1) * pageSize < response.total,
                total = response.total,
            )
        }
    }

    override suspend fun wantToRead(server: Server): Outcome<List<BookSummary>> = call {
        api.dashboardScroller(
            server.resolve("/api/v1/dashboard/scrollers/want-to-read?limit=50"),
        ).map { it.toSummary(server) }
    }

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = call {
        val book = api.book(server.resolve("/api/v1/books/$bookId"))
        val summary = book.toSummary(server)
        val file = book.primaryFile
        // issue #192 — server 2.10.0 404s `files/{id}/serve` for audio formats; audiobooks
        // stream from a dedicated per-asset route instead, discovered via the manifest. Null
        // on a pre-2.10 server (no manifest at all) — falls through to the old `file`-based
        // acquisition below, which still works there since only 2.10+ 404s it for audio.
        val audiobookAsset = if (summary.format == ContentFormat.AUDIOBOOK) {
            api.audiobookManifestOrNull(server.resolve("/api/v1/audiobooks/$bookId/manifest"))
                ?.assets?.minByOrNull { it.sequence }
        } else {
            null
        }
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
            rating = book.rating,
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
                when {
                    audiobookAsset != null -> Acquisition(
                        href = server.resolve("/api/v1/audiobooks/$bookId/assets/${audiobookAsset.assetId}/content"),
                        mediaType = summary.format.name,
                        format = summary.format,
                        relation = AcquisitionRelation.ACQUIRE,
                        sizeBytes = file?.sizeBytes,
                    )
                    file != null -> Acquisition(
                        href = server.resolve("/api/v1/books/files/${file.id}/serve"),
                        mediaType = summary.format.name,
                        format = summary.format,
                        relation = AcquisitionRelation.ACQUIRE,
                        sizeBytes = file.sizeBytes,
                    )
                    else -> null
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
        seriesId = seriesId?.toString(),
    )

    // BookOrbit's sort fields (packages/types SortField): author, title, series, seriesIndex,
    // addedAt, … — NOT `seriesName`, which the query pipe rejects with a 400.
    private fun sortModel(sort: BookSort): List<BookOrbitSort> = when (sort) {
        BookSort.RECENT -> listOf(BookOrbitSort("addedAt", "desc"))
        BookSort.TITLE -> listOf(BookOrbitSort("title", "asc"))
        BookSort.SERIES -> listOf(BookOrbitSort("series", "asc"), BookOrbitSort("seriesIndex", "asc"))
    }

    /** Best-effort file extension: the server's `format` field, else the filename's suffix. */
    private fun fileExtensionOf(format: String?, filename: String?): String? {
        format?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { return it }
        return filename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
    }

    /**
     * issue #287 — [formats] as a server-side `format includesAny [...]` rule: the file formats
     * [formatOf] maps to each. Filtering only the returned page left "All libraries" + Comics
     * paging through a large library's other books (thousands, on a big server) before any
     * comic turned up. Null (no filter) for all formats, or none BookOrbit can name.
     */
    private fun formatFilter(formats: Set<ContentFormat>?): BookOrbitFilterGroup? {
        val names = formats?.flatMap { FILE_FORMATS[it].orEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
        return BookOrbitFilterGroup(
            type = "group",
            join = "AND",
            rules = listOf(BookOrbitFilterRule(type = "rule", field = "format", operator = "includesAny", value = names)),
        )
    }

    private fun formatOf(format: String?): ContentFormat = when (format?.lowercase()) {
        "epub", "kepub" -> ContentFormat.EPUB
        "pdf" -> ContentFormat.PDF
        "cbz", "cbr" -> ContentFormat.COMIC // cb7 deliberately excluded — issue #110
        "m4b", "mp3", "m4a", "opus", "ogg", "flac", "aac" -> ContentFormat.AUDIOBOOK
        "mobi", "prc" -> ContentFormat.MOBI
        "azw3", "azw" -> ContentFormat.AZW3
        "fb2" -> ContentFormat.FB2
        else -> ContentFormat.UNKNOWN
    }

    private companion object {
        /** The series endpoints' own `@Max(100)` page-size cap. */
        const val MAX_SERIES_PAGE = 100

        /** The server's file format names per content format — [formatOf] in reverse. */
        val FILE_FORMATS: Map<ContentFormat, List<String>> = mapOf(
            ContentFormat.EPUB to listOf("epub", "kepub"),
            ContentFormat.PDF to listOf("pdf"),
            ContentFormat.COMIC to listOf("cbz", "cbr"),
            ContentFormat.AUDIOBOOK to listOf("m4b", "mp3", "m4a", "opus", "ogg", "flac", "aac"),
            ContentFormat.MOBI to listOf("mobi", "prc"),
            ContentFormat.AZW3 to listOf("azw3", "azw"),
            ContentFormat.FB2 to listOf("fb2"),
        )
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
