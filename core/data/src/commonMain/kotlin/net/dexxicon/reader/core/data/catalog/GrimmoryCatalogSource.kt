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
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBook
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryFacet
import net.dexxicon.reader.core.serverapi.browse.GrimmoryFacetLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.ktor.client.plugins.ResponseException
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.io.IOException
import kotlin.math.roundToInt

/** Browses Grimmory / BookLore via its native REST API (uses the JWT bearer). */
class GrimmoryCatalogSource(
    private val api: GrimmoryBrowseApi,
) : CatalogSource {

    // BookLore's `file_type` facet (CBX/EPUB/AUDIOBOOK/PDF/MOBI) — the grouping that actually
    // matters to a reader.
    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        val fileType = facetValues(server, "file_type", search = null, page = 0, size = MAX_PAGE)
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
            parameters.append("size", pageSize.coerceIn(1, MAX_PAGE).toString())
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

    // issues #253/#254/#256, #335 — libraries, shelves and series through Grimmory's supported
    // web API: each is a facet of /books/page (`library:`, `shelf:`, `shelf:magic:`, `series:`).
    override suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> = call {
        fun group(id: Long, name: String, count: Int?) =
            BookGroup(server.id, kind, id.toString(), name, count, serverName = server.displayName)
        when (kind) {
            BookGroupKind.LIBRARY -> {
                // No book count on a library — ask /books/page for one per library.
                val libraries = api.libraries(server.resolve("/api/v1/libraries"))
                val counts = countEach(server, libraries.map { "library:${it.id}" })
                libraries.mapIndexed { i, it -> group(it.id, it.name, counts[i]) }
            }
            BookGroupKind.COLLECTION -> api.shelves(server.resolve("/api/v1/shelves"))
                .map { group(it.id, it.name, it.bookCount) }
            BookGroupKind.SMART -> {
                // Note the path: magic shelves have no `/v1` (MagicShelfController).
                val shelves = api.magicShelves(server.resolve("/api/magic-shelves"))
                val counts = countEach(server, shelves.map { "shelf:magic:${it.id}" })
                shelves.mapIndexed { i, it -> group(it.id, it.name, counts[i]) }
            }
            BookGroupKind.SERIES -> emptyList()
        }
    }

    /**
     * Grimmory has no series endpoint; its series are values of the `series` facet, ordered by
     * book count. Unreleased Grimmory pages them (`/books/facets/series`); 3.5.0 returns the
     * 100 largest with every other facet, so there the list stops at 100 series.
     */
    override suspend fun series(
        server: Server,
        query: String?,
        page: Int,
        pageSize: Int,
    ): Outcome<BookGroupPage> = call {
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        val size = pageSize.coerceIn(1, MAX_PAGE)
        val paged = api.facetOrNull(facetUrl(server, "series", search, page, size))
        val (values, hasMore) = if (paged != null) {
            paged.facets.firstOrNull()?.links.orEmpty() to paged.hasNext
        } else if (page == 0) {
            val all = api.facets(
                URLBuilder(server.resolve("/api/v1/books/facets")).apply {
                    search?.let { parameters.append("query", it) }
                }.buildString(),
            ).facets.firstOrNull { it.metadata.key == "series" }?.links.orEmpty()
            all.filter { search == null || it.value?.contains(search, ignoreCase = true) == true } to false
        } else {
            emptyList<GrimmoryFacetLink>() to false
        }
        val named = values.mapNotNull { link -> link.value?.takeIf { it.isNotBlank() }?.let { it to link } }
        // A cover and authors for each series from its first book (a few requests at a time).
        val firstBooks = forEachLimited(named.map { it.first }) { name ->
            api.booksPage(pageUrl(server, 0, 1, "seriesNumber", null, listOf("series:$name")))
                .content.firstOrNull()
        }
        BookGroupPage(
            groups = named.mapIndexed { i, (name, link) ->
                val first = firstBooks[i]
                BookGroup(
                    serverId = server.id,
                    kind = BookGroupKind.SERIES,
                    // Grimmory keys series by name — there's no series id to use instead.
                    id = name,
                    name = name,
                    bookCount = link.properties.numberOfItems,
                    coverUrl = first?.let { server.resolve(coverPath(it.id, it.format)) },
                    serverName = server.displayName,
                    authors = first?.metadata?.authors.orEmpty(),
                )
            },
            hasMore = hasMore,
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
        val facet = when (group.kind) {
            BookGroupKind.LIBRARY -> "library:${group.id}"
            BookGroupKind.COLLECTION -> "shelf:${group.id}"
            BookGroupKind.SMART -> "shelf:magic:${group.id}"
            BookGroupKind.SERIES -> "series:${group.id}"
        }
        // A series always reads in series order.
        val sortKey = if (group.kind == BookGroupKind.SERIES) "seriesNumber" else sortKey(sort)
        val response = api.booksPage(pageUrl(server, page, pageSize.coerceIn(1, MAX_PAGE), sortKey, query, listOf(facet)))
        val totalPages = response.effectiveTotalPages
        BookPage(
            books = response.content.map { it.toSummary(server) },
            page = page,
            hasMore = totalPages?.let { response.effectiveNumber + 1 < it } ?: false,
            total = response.effectiveTotal?.toInt(),
        )
    }

    /** `/books/page` with Grimmory's facet parameters. Always sends `sort` — without it (and
     *  no facet or query) the server answers in its legacy page shape. */
    private fun pageUrl(
        server: Server,
        page: Int,
        size: Int,
        sort: String,
        query: String?,
        facets: List<String>,
    ): String = URLBuilder(server.resolve("/api/v1/books/page")).apply {
        parameters.append("page", page.toString())
        parameters.append("size", size.toString())
        parameters.append("sort", sort)
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters.append("query", it) }
        facets.forEach { parameters.append("facet", it) }
    }.buildString()

    private fun facetUrl(server: Server, name: String, search: String?, page: Int, size: Int): String =
        URLBuilder(server.resolve("/api/v1/books/facets")).apply {
            appendPathSegments(name)
            parameters.append("page", page.toString())
            parameters.append("size", size.toString())
            search?.let { parameters.append("search", it) }
        }.buildString()

    /** One facet's values: the paged endpoint where the server has it, else 3.5.0's
     *  every-facet response (whose `/books/facets` the paged one replaces). */
    private suspend fun facetValues(server: Server, name: String, search: String?, page: Int, size: Int): GrimmoryFacet? =
        api.facetOrNull(facetUrl(server, name, search, page, size))?.facets?.firstOrNull()
            ?: api.facets(server.resolve("/api/v1/books/facets")).facets.firstOrNull { it.metadata.key == name }

    /** How many books match each facet — `totalElements` of a one-book page. */
    private suspend fun countEach(server: Server, facets: List<String>): List<Int?> =
        forEachLimited(facets) { facet ->
            // Not `sort=id`: Grimmory reserves `id` for its own tiebreaker and answers it with a 400.
            api.booksPage(pageUrl(server, 0, 1, "addedOn", null, listOf(facet))).effectiveTotal?.toInt()
        }

    /** [block] for each item, [PARALLEL] at a time; a failure is null for that item only. */
    private suspend fun <T, R> forEachLimited(items: List<T>, block: suspend (T) -> R?): List<R?> {
        val limit = Semaphore(PARALLEL)
        return coroutineScope {
            items.map { item ->
                async { limit.withPermit { runCatching { block(item) }.getOrNull() } }
            }.awaitAll()
        }
    }

    private val GrimmoryBook.format: ContentFormat
        get() = formatOf(primaryFile?.bookType, primaryFile?.extension)

    /** Audiobooks are served from a separate cover route; the generic one 404s. */
    private fun coverPath(bookId: Long, format: ContentFormat): String =
        if (format == ContentFormat.AUDIOBOOK) {
            "/api/v1/media/book/$bookId/audiobook-cover"
        } else {
            "/api/v1/media/book/$bookId/cover"
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
        /** `/books/page`'s limit: 3.5.0 clamps to it, later Grimmory answers 400 above it. */
        const val MAX_PAGE = 100

        /** Concurrent per-facet requests (series covers, library counts). */
        const val PARALLEL = 6
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
