package net.dexxicon.reader.core.data.catalog

import io.ktor.client.plugins.ResponseException
import io.ktor.http.URLBuilder
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookGroupPage
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.browse.KomgaAuthor
import net.dexxicon.reader.core.serverapi.browse.KomgaBook
import net.dexxicon.reader.core.serverapi.browse.KomgaBrowseApi
import net.dexxicon.reader.core.serverapi.browse.KomgaPage
import net.dexxicon.reader.core.serverapi.browse.KomgaSeries

/**
 * issue #327 — browses Komga through its own REST API (see [KomgaBrowseApi]). A Komga book is
 * one file, so it maps straight onto the app's books; series, libraries, collections (of series)
 * and read lists (of books) become the app's groups. Collections and read lists share
 * [BookGroupKind.COLLECTION], told apart by an id prefix ([COLLECTION_PREFIX] / [READ_LIST_PREFIX]).
 */
class KomgaCatalogSource(
    private val api: KomgaBrowseApi,
) : CatalogSource {

    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        api.libraries(server.resolve("/api/v1/libraries")).map { CatalogShelf(id = it.id, title = it.name) }
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
        val conditions = buildList {
            shelfId?.let { add(condition("libraryId", it)) }
            formatCondition(formats)?.let(::add)
        }
        listBooks(server, conditions, query, sortParam(sort), page, pageSize)
    }

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = call {
        val book = api.book(server.resolve("/api/v1/books/$bookId"))
        // The series carries the publisher, language and genres; a book reads fine without it.
        val series = book.seriesId?.let { id -> runCatching { api.series(server.resolve("/api/v1/series/$id")) }.getOrNull() }
        val format = formatOf(book)
        BookDetail(
            summary = book.toSummary(server),
            description = book.metadata.summary?.takeIf { it.isNotBlank() }
                ?: series?.metadata?.summary?.takeIf { it.isNotBlank() },
            publisher = series?.metadata?.publisher?.takeIf { it.isNotBlank() },
            publishedDate = book.metadata.releaseDate,
            language = series?.metadata?.language?.takeIf { it.isNotBlank() },
            isbn = book.metadata.isbn?.takeIf { it.isNotBlank() },
            pageCount = book.media.pagesCount.takeIf { it > 0 && format != ContentFormat.EPUB },
            categories = (series?.metadata?.genres.orEmpty() + book.metadata.tags).distinct(),
            readingStatus = book.readingStatus(),
            fileExtension = book.url?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() },
            fileSizeBytes = book.sizeBytes,
            acquisitions = listOf(
                Acquisition(
                    href = server.resolve("/api/v1/books/${book.id}/file"),
                    mediaType = book.media.mediaType ?: format.name,
                    format = format,
                    // Komga ignores range requests and always sends the whole file, so readers
                    // fetch it in one go (as for an OPDS catalog's files) instead of streaming.
                    relation = AcquisitionRelation.OPEN_ACCESS,
                    sizeBytes = book.sizeBytes,
                ),
            ),
        )
    }

    /** Komga's "on deck": the next unread book of each series the user is part-way through. */
    override suspend fun wantToRead(server: Server): Outcome<List<BookSummary>> = call {
        api.bookPage(server.resolve("/api/v1/books/ondeck?size=$ON_DECK_SIZE")).content.map { it.toSummary(server) }
    }

    override suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> = call {
        when (kind) {
            BookGroupKind.LIBRARY -> api.libraries(server.resolve("/api/v1/libraries")).map {
                BookGroup(server.id, kind, it.id, it.name, serverName = server.displayName)
            }
            BookGroupKind.COLLECTION -> {
                val collections = api.collections(server.resolve("/api/v1/collections?unpaged=true")).content.map {
                    BookGroup(server.id, kind, COLLECTION_PREFIX + it.id, it.name, serverName = server.displayName)
                }
                val readLists = api.readLists(server.resolve("/api/v1/readlists?unpaged=true")).content.map {
                    BookGroup(
                        server.id, kind, READ_LIST_PREFIX + it.id, it.name,
                        bookCount = it.bookIds.size,
                        coverUrl = server.resolve("/api/v1/readlists/${it.id}/thumbnail"),
                        serverName = server.displayName,
                    )
                }
                collections + readLists
            }
            BookGroupKind.SMART, BookGroupKind.SERIES -> emptyList()
        }
    }

    override suspend fun series(server: Server, query: String?, page: Int, pageSize: Int): Outcome<BookGroupPage> = call {
        val url = URLBuilder(server.resolve("/api/v1/series")).apply {
            parameters.append("page", page.toString())
            parameters.append("size", pageSize.toString())
            parameters.append("sort", "metadata.titleSort,asc")
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters.append("search", it) }
        }.buildString()
        val response = api.seriesPage(url)
        BookGroupPage(
            groups = response.content.map { it.toGroup(server) },
            hasMore = !response.last,
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
        when (group.kind) {
            BookGroupKind.LIBRARY -> {
                val conditions = listOfNotNull(condition("libraryId", group.id), formatCondition(formats))
                listBooks(server, conditions, query, sortParam(sort), page, pageSize)
            }
            // A series always reads in its own order.
            BookGroupKind.SERIES ->
                listBooks(server, listOf(condition("seriesId", group.id)), query, SERIES_ORDER, page, pageSize)
            BookGroupKind.COLLECTION -> when {
                group.id.startsWith(READ_LIST_PREFIX) -> {
                    val id = group.id.removePrefix(READ_LIST_PREFIX)
                    val response = api.bookPage(server.resolve("/api/v1/readlists/$id/books?page=$page&size=$pageSize"))
                    response.toBookPage(server, page)
                }
                else -> {
                    val id = group.id.removePrefix(COLLECTION_PREFIX)
                    val seriesIds = api.collection(server.resolve("/api/v1/collections/$id")).seriesIds
                    if (seriesIds.isEmpty()) {
                        BookPage(emptyList(), page, hasMore = false, total = 0)
                    } else {
                        val anySeries = buildJsonObject {
                            put("anyOf", buildJsonArray { seriesIds.forEach { add(condition("seriesId", it)) } })
                        }
                        listBooks(server, listOfNotNull(anySeries, formatCondition(formats)), query, sortParam(sort), page, pageSize)
                    }
                }
            }
            BookGroupKind.SMART -> BookPage(emptyList(), page, hasMore = false, total = 0)
        }
    }

    private suspend fun listBooks(
        server: Server,
        conditions: List<JsonObject>,
        query: String?,
        sort: String,
        page: Int,
        pageSize: Int,
    ): BookPage {
        val url = URLBuilder(server.resolve("/api/v1/books/list")).apply {
            parameters.append("page", page.toString())
            parameters.append("size", pageSize.toString())
            sort.split(';').forEach { parameters.append("sort", it) }
        }.buildString()
        val body = buildJsonObject {
            when (conditions.size) {
                0 -> Unit
                1 -> put("condition", conditions.single())
                else -> put("condition", buildJsonObject { put("allOf", buildJsonArray { conditions.forEach { add(it) } }) })
            }
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { put("fullTextSearch", it) }
        }
        return api.bookList(url, body).toBookPage(server, page)
    }

    private fun KomgaPage<KomgaBook>.toBookPage(server: Server, page: Int) = BookPage(
        books = content.map { it.toSummary(server) },
        page = page,
        hasMore = !last,
        total = totalElements.toInt(),
    )

    private fun KomgaBook.toSummary(server: Server) = BookSummary(
        id = id,
        serverId = server.id,
        title = title,
        authors = metadata.authors.names(),
        series = seriesTitle?.takeIf { it.isNotBlank() },
        seriesIndex = metadata.numberSort,
        coverUrl = server.resolve("/api/v1/books/$id/thumbnail"),
        format = formatOf(this),
        shelfId = libraryId,
        seriesId = seriesId,
    )

    private fun KomgaSeries.toGroup(server: Server) = BookGroup(
        serverId = server.id,
        kind = BookGroupKind.SERIES,
        id = id,
        name = title,
        bookCount = booksCount,
        coverUrl = server.resolve("/api/v1/series/$id/thumbnail"),
        serverName = server.displayName,
        authors = booksMetadata.authors.names(),
    )

    private suspend inline fun <T> call(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (e: ResponseException) {
        val status = e.response.status.value
        when (status) {
            401, 403 -> Outcome.Failure(DexxiconError.Unauthorized("Komga refused the sign-in — check the password in Settings"))
            // issue #251 — "gone" has to be told apart from "unreachable" for Home to prune.
            404 -> Outcome.Failure(DexxiconError.NotFound("That's no longer on the server"))
            else -> Outcome.Failure(DexxiconError.Network("Server returned HTTP $status"))
        }
    } catch (e: IOException) {
        Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
    } catch (e: Exception) {
        Outcome.Failure(DexxiconError.Unknown(e.message, e))
    }

    internal companion object {
        const val COLLECTION_PREFIX = "c:"
        const val READ_LIST_PREFIX = "r:"
        private const val ON_DECK_SIZE = 50

        /** Komga's own series order: by number, then title. */
        const val SERIES_ORDER = "metadata.numberSort,asc;metadata.title,asc"

        fun sortParam(sort: BookSort): String = when (sort) {
            BookSort.RECENT -> "createdDate,desc"
            BookSort.TITLE -> "metadata.title,asc"
            BookSort.SERIES -> "series,asc;metadata.numberSort,asc"
        }

        /** `{"<field>": {"operator": "is", "value": "<value>"}}`, Komga's search condition. */
        fun condition(field: String, value: String): JsonObject = buildJsonObject {
            putJsonObject(field) {
                put("operator", "is")
                put("value", value)
            }
        }

        /** The app's formats as Komga media profiles; null when nothing narrows the list. */
        fun formatCondition(formats: Set<ContentFormat>?): JsonObject? {
            val profiles = formats.orEmpty().mapNotNull {
                when (it) {
                    ContentFormat.EPUB -> "EPUB"
                    ContentFormat.PDF -> "PDF"
                    ContentFormat.COMIC -> "DIVINA"
                    else -> null
                }
            }.distinct()
            if (formats == null || profiles.isEmpty()) return null
            return if (profiles.size == 1) {
                condition("mediaProfile", profiles.single())
            } else {
                buildJsonObject { put("anyOf", buildJsonArray { profiles.forEach { add(condition("mediaProfile", it)) } }) }
            }
        }

        fun formatOf(book: KomgaBook): ContentFormat = when (book.media.mediaProfile?.uppercase()) {
            "EPUB" -> ContentFormat.EPUB
            "PDF" -> ContentFormat.PDF
            "DIVINA" -> ContentFormat.COMIC
            else -> when {
                book.media.mediaType?.contains("epub") == true -> ContentFormat.EPUB
                book.media.mediaType?.contains("pdf") == true -> ContentFormat.PDF
                else -> ContentFormat.UNKNOWN
            }
        }

        fun KomgaBook.readingStatus(): ReadingStatus {
            val progress = readProgress ?: return ReadingStatus.UNREAD
            return if (progress.completed) ReadingStatus.READ else ReadingStatus.READING
        }

        /** Writers first (a comic credits a dozen people), else everyone named, once each. */
        fun List<KomgaAuthor>.names(): List<String> {
            val writers = filter { it.role.equals("writer", ignoreCase = true) || it.role.equals("author", ignoreCase = true) }
            return (writers.ifEmpty { this }).map { it.name }.distinct()
        }
    }
}
