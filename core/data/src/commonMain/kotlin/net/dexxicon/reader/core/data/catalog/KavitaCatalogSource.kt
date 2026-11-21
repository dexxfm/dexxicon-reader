package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.model.PageStreamInfo
import io.ktor.client.plugins.ResponseException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.IOException
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.htmlToPlainText
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
import net.dexxicon.reader.core.serverapi.browse.KavitaBrowseApi
import net.dexxicon.reader.core.serverapi.browse.KavitaChapter
import net.dexxicon.reader.core.serverapi.browse.KavitaFilterStatement
import net.dexxicon.reader.core.serverapi.browse.KavitaReadingListItem
import net.dexxicon.reader.core.serverapi.browse.KavitaSeries
import net.dexxicon.reader.core.serverapi.browse.KavitaSeriesFilter
import net.dexxicon.reader.core.serverapi.browse.KavitaSortOptions
import net.dexxicon.reader.core.serverapi.browse.KavitaVolume

/**
 * issue #328 — browses Kavita through its own REST API (see [KavitaBrowseApi]).
 *
 * Kavita keeps series → volumes → chapters, and a chapter (usually one file) is what it reads,
 * tracks and downloads, so a chapter is the app's book: its id is the book id. Libraries list
 * series, the way Kavita's own app does: a series of one file shows as that book, and a series
 * of several shows as one card that opens the series' list ([BookSummary.opensGroup]). Telling
 * them apart takes each series' volumes, fetched alongside the page and kept for a few minutes.
 *
 * Collections (of series) and reading lists (of chapters) share [BookGroupKind.COLLECTION],
 * told apart by an id prefix, as for Komga.
 */
@OptIn(ExperimentalTime::class)
class KavitaCatalogSource(
    private val api: KavitaBrowseApi,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CatalogSource {

    private val cacheLock = Mutex()
    private val volumesCache = mutableMapOf<String, Pair<Long, List<KavitaVolume>>>()
    private val seriesCache = mutableMapOf<String, KavitaSeries>()
    private val libraryTypes = mutableMapOf<String, Pair<Long, Map<Int, Int>>>()
    private val imageKeys = mutableMapOf<String, String>()

    /** Caps the volume lookups a page of series sets off at once. */
    private val lookups = Semaphore(PARALLEL_LOOKUPS)

    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        api.libraries(server.resolve("/api/Library/libraries")).map { CatalogShelf(id = it.id.toString(), title = it.name) }
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
        val statements = listOfNotNull(
            shelfId?.let { statement(FIELD_LIBRARY, EQUAL, it) },
            nameStatement(query),
            formatStatement(formats),
        )
        seriesCards(server, statements, sortOptions(sort), page, pageSize)
    }

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = call {
        val chapterId = bookId.removePrefix(SERIES_PREFIX).toIntOrNull()
            ?: throw IllegalArgumentException("Not a Kavita chapter id: $bookId")
        // A series card's own id (it never reaches here from the app's lists, but a stale link
        // might): open the chapter Kavita would carry on with.
        val chapter = if (bookId.startsWith(SERIES_PREFIX)) {
            api.continuePoint(server.resolve("/api/Reader/continue-point?seriesId=$chapterId"))
        } else {
            api.chapter(server.resolve("/api/Chapter?chapterId=$chapterId"))
        }
        val series = api.seriesForChapter(server.resolve("/api/Search/series-for-chapter?chapterId=${chapter.id}"))
        remember(server, series)
        val volumes = volumesOf(server, series.id)
        val volume = volumes.firstOrNull { v -> v.chapters.any { it.id == chapter.id } }
        val metadata = runCatching { api.seriesMetadata(server.resolve("/api/Series/metadata?seriesId=${series.id}")) }.getOrNull()
        val format = formatOf(chapter.format, chapter.extension())
        // issue #349 — several files in one chapter have no single file to fetch (Kavita's own
        // download is an empty ZIP for them), so they're read page by page, as Kavita's reader does.
        val pageStream = if (chapter.files.size > 1 && format == ContentFormat.COMIC && chapter.pages > 0) {
            imageKey(server)?.let { key ->
                PageStreamInfo(
                    hrefTemplate = server.resolve(
                        "/api/Reader/image?chapterId=${chapter.id}&apiKey=$key&page=${PageStreamInfo.PAGE_NUMBER}",
                    ),
                    count = chapter.pages,
                    mediaType = "image/jpeg",
                )
            }
        } else {
            null
        }
        val summary = chapter.toSummary(server, series, volume, single = volumes.chapterCount() == 1, libraryType(server, series.libraryId))
        BookDetail(
            summary = summary.copy(
                authors = summary.authors.ifEmpty { metadata?.writers.orEmpty().map { it.name }.filter { it.isNotBlank() }.distinct() },
            ),
            // Kavita keeps summaries as HTML.
            description = (chapter.summary?.takeIf { it.isNotBlank() } ?: metadata?.summary)
                ?.htmlToPlainText()?.takeIf { it.isNotBlank() },
            publisher = (chapter.publishers.ifEmpty { metadata?.publishers.orEmpty() }).firstOrNull()?.name?.takeIf { it.isNotBlank() },
            publishedDate = chapter.releaseDate?.takeUnless { it.startsWith("0001") }?.substringBefore('T'),
            language = chapter.language?.takeIf { it.isNotBlank() } ?: metadata?.language?.takeIf { it.isNotBlank() },
            isbn = chapter.isbn?.takeIf { it.isNotBlank() },
            // An EPUB's "pages" in Kavita are its sections, not pages.
            pageCount = chapter.pages.takeIf { it > 0 && format != ContentFormat.EPUB },
            categories = (chapter.genres + chapter.tags + metadata?.genres.orEmpty())
                .map { it.title }.filter { it.isNotBlank() }.distinct(),
            readingStatus = chapter.readingStatus(),
            pageStream = pageStream,
            fileExtension = chapter.extension(),
            fileSizeBytes = chapter.files.sumOf { it.bytes }.takeIf { it > 0 },
            acquisitions = listOf(
                Acquisition(
                    href = server.resolve("/api/Download/chapter?chapterId=${chapter.id}"),
                    mediaType = mediaTypeOf(format, chapter.extension()),
                    format = format,
                    // Kavita's download route answers GET only: the HEAD a streaming reader
                    // opens with is a 404, so readers fetch the file in one go (as for Komga).
                    relation = AcquisitionRelation.OPEN_ACCESS,
                    sizeBytes = chapter.files.sumOf { it.bytes }.takeIf { it > 0 },
                ),
            ),
        )
    }

    /** Kavita's "on deck": for each series in progress, the chapter to carry on with. */
    override suspend fun wantToRead(server: Server): Outcome<List<BookSummary>> = call {
        val series = api.onDeck(server.resolve("/api/Series/on-deck?PageNumber=1&PageSize=$ON_DECK_SIZE"))
        series.forEach { remember(server, it) }
        coroutineScope {
            series.map { s ->
                async {
                    lookups.withPermit {
                        runCatching {
                            val chapter = api.continuePoint(server.resolve("/api/Reader/continue-point?seriesId=${s.id}"))
                            val volumes = volumesOf(server, s.id)
                            val volume = volumes.firstOrNull { v -> v.chapters.any { it.id == chapter.id } }
                            chapter.toSummary(server, s, volume, single = volumes.chapterCount() == 1, libraryType(server, s.libraryId))
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * The chapters a reader is part-way through, newest first, with how far along each is —
     * for Continue reading ([net.dexxicon.reader.core.data.sync.LibrarySeeder]).
     */
    suspend fun inProgress(server: Server): List<Pair<BookSummary, Double>> {
        val filter = KavitaSeriesFilter.allOf(
            listOf(statement(FIELD_READ_PROGRESS, GREATER_THAN, "0"), statement(FIELD_READ_PROGRESS, LESS_THAN, "100")),
            KavitaSortOptions(SORT_READ_PROGRESS, isAscending = false),
        )
        val series = api.seriesPage(server.resolve("/api/Series/all-v2?PageNumber=1&PageSize=$IN_PROGRESS_SIZE"), filter).items
        series.forEach { remember(server, it) }
        return coroutineScope {
            series.map { s ->
                async {
                    lookups.withPermit {
                        runCatching {
                            val volumes = volumesOf(server, s.id, fresh = true)
                            val single = volumes.chapterCount() == 1
                            val type = libraryType(server, s.libraryId)
                            volumes.flatMap { v -> v.chapters.map { v to it } }
                                .filter { (_, c) -> c.pages > 0 && c.pagesRead in 1 until c.pages }
                                .map { (v, c) -> c.toSummary(server, s, v, single, type) to c.pagesRead.toDouble() / c.pages }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
    }

    override suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> = call {
        when (kind) {
            BookGroupKind.LIBRARY -> api.libraries(server.resolve("/api/Library/libraries")).map {
                BookGroup(server.id, kind, it.id.toString(), it.name, serverName = server.displayName)
            }
            BookGroupKind.COLLECTION -> {
                val collections = api.collections(server.resolve("/api/Collection?ownedOnly=false")).map {
                    BookGroup(
                        server.id, kind, COLLECTION_PREFIX + it.id, it.title,
                        bookCount = it.itemCount.takeIf { n -> n > 0 },
                        coverUrl = server.resolve("/api/Image/collection-cover?collectionTagId=${it.id}"),
                        serverName = server.displayName,
                    )
                }
                val readingLists = api.readingLists(
                    server.resolve("/api/ReadingList/lists?PageNumber=1&PageSize=$ALL&includePromoted=true"),
                ).map {
                    BookGroup(
                        server.id, kind, READING_LIST_PREFIX + it.id, it.title,
                        bookCount = it.itemCount.takeIf { n -> n > 0 },
                        coverUrl = server.resolve("/api/Image/readinglist-cover?readingListId=${it.id}"),
                        serverName = server.displayName,
                    )
                }
                collections + readingLists
            }
            BookGroupKind.SMART, BookGroupKind.SERIES -> emptyList()
        }
    }

    override suspend fun series(server: Server, query: String?, page: Int, pageSize: Int): Outcome<BookGroupPage> = call {
        val response = api.seriesPage(
            server.resolve("/api/Series/all-v2?PageNumber=${page + 1}&PageSize=$pageSize"),
            KavitaSeriesFilter.allOf(listOfNotNull(nameStatement(query)), KavitaSortOptions(SORT_NAME, isAscending = true)),
        )
        response.items.forEach { remember(server, it) }
        BookGroupPage(groups = response.items.map { it.toGroup(server, bookCount = null) }, hasMore = response.hasMore(pageSize))
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
                val statements = listOfNotNull(statement(FIELD_LIBRARY, EQUAL, group.id), nameStatement(query), formatStatement(formats))
                seriesCards(server, statements, sortOptions(sort), page, pageSize)
            }
            // Every chapter, in Kavita's reading order: volumes, then loose chapters, then specials.
            BookGroupKind.SERIES -> {
                val seriesId = group.id.toIntOrNull() ?: throw IllegalArgumentException("Not a Kavita series id: ${group.id}")
                val series = seriesOf(server, seriesId)
                val volumes = volumesOf(server, seriesId, fresh = page == 0)
                val single = volumes.chapterCount() == 1
                val type = libraryType(server, series.libraryId)
                val all = volumes.flatMap { v -> v.chapters.map { v to it } }
                    .sortedWith(compareBy({ (v, c) -> readingGroup(v, c) }, { (v, _) -> v.minNumber }, { (_, c) -> c.sortOrder }))
                    .map { (v, c) -> c.toSummary(server, series, v, single, type) }
                all.pageOf(page, pageSize)
            }
            BookGroupKind.COLLECTION -> when {
                group.id.startsWith(READING_LIST_PREFIX) -> {
                    val id = group.id.removePrefix(READING_LIST_PREFIX)
                    val items = api.readingListItems(server.resolve("/api/ReadingList/items?readingListId=$id"))
                    items.sortedBy { it.order }.map { it.toSummary(server) }.pageOf(page, pageSize)
                }
                else -> {
                    val id = group.id.removePrefix(COLLECTION_PREFIX)
                    val statements = listOfNotNull(statement(FIELD_COLLECTION, EQUAL, id), nameStatement(query), formatStatement(formats))
                    seriesCards(server, statements, sortOptions(sort), page, pageSize)
                }
            }
            BookGroupKind.SMART -> BookPage(emptyList(), page, hasMore = false, total = 0)
        }
    }

    // ---- series as cards ----

    private suspend fun seriesCards(
        server: Server,
        statements: List<KavitaFilterStatement>,
        sort: KavitaSortOptions,
        page: Int,
        pageSize: Int,
    ): BookPage {
        val response = api.seriesPage(
            server.resolve("/api/Series/all-v2?PageNumber=${page + 1}&PageSize=$pageSize"),
            KavitaSeriesFilter.allOf(statements, sort),
        )
        response.items.forEach { remember(server, it) }
        val types = libraryTypes(server)
        val cards = coroutineScope {
            response.items.map { series ->
                async { lookups.withPermit { card(server, series, types[series.libraryId]) } }
            }.awaitAll().filterNotNull()
        }
        return BookPage(
            books = cards,
            page = page,
            hasMore = response.hasMore(pageSize),
            total = response.pagination?.totalItems,
        )
    }

    /** One file: that book. Several: a card opening the series. Its volumes unreadable: the card. */
    private suspend fun card(server: Server, series: KavitaSeries, libraryType: Int?): BookSummary? {
        val volumes = runCatching { volumesOf(server, series.id) }.getOrNull()
        val chapters = volumes?.flatMap { v -> v.chapters.map { v to it } }
        if (chapters != null && chapters.isEmpty()) return null
        if (chapters != null && chapters.size == 1) {
            val (volume, chapter) = chapters.single()
            return chapter.toSummary(server, series, volume, single = true, libraryType)
        }
        val group = series.toGroup(server, bookCount = chapters?.size)
        return BookSummary(
            id = SERIES_PREFIX + series.id,
            serverId = server.id,
            title = series.name,
            authors = chapters?.firstNotNullOfOrNull { (_, c) -> c.writerNames().ifEmpty { null } }.orEmpty(),
            coverUrl = group.coverUrl,
            format = formatOf(series.format, null),
            shelfId = series.libraryId.toString(),
            seriesId = series.id.toString(),
            opensGroup = group,
        )
    }

    private fun KavitaSeries.toGroup(server: Server, bookCount: Int?) = BookGroup(
        serverId = server.id,
        kind = BookGroupKind.SERIES,
        id = id.toString(),
        name = name,
        bookCount = bookCount,
        coverUrl = server.resolve("/api/Image/series-cover?seriesId=$id"),
        serverName = server.displayName,
    )

    private fun KavitaChapter.toSummary(
        server: Server,
        series: KavitaSeries,
        volume: KavitaVolume?,
        single: Boolean,
        libraryType: Int?,
    ) = BookSummary(
        id = id.toString(),
        serverId = server.id,
        title = bookTitle(series.name, this, volume, single, libraryType),
        authors = writerNames(),
        series = series.name.takeUnless { single },
        seriesIndex = seriesIndexOf(this, volume).takeUnless { single },
        // A lone book wears its series' cover (the one Kavita shows); a volume or issue its own.
        coverUrl = if (single) {
            server.resolve("/api/Image/series-cover?seriesId=${series.id}")
        } else {
            server.resolve("/api/Image/chapter-cover?chapterId=$id")
        },
        format = formatOf(format, extension()),
        shelfId = series.libraryId.toString(),
        seriesId = series.id.toString(),
    )

    private fun KavitaReadingListItem.toSummary(server: Server): BookSummary {
        val seriesName = seriesName?.takeIf { it.isNotBlank() }
        val label = title?.takeIf { it.isNotBlank() }
        return BookSummary(
            id = chapterId.toString(),
            serverId = server.id,
            title = when {
                seriesName == null -> label ?: "Chapter $chapterId"
                label == null || label.contains(seriesName, ignoreCase = true) -> label ?: seriesName
                else -> "$seriesName $label"
            },
            series = seriesName,
            coverUrl = server.resolve("/api/Image/chapter-cover?chapterId=$chapterId"),
            format = formatOf(seriesFormat, null),
            shelfId = libraryId.toString(),
            seriesId = seriesId.toString(),
        )
    }

    // ---- caches ----

    private suspend fun volumesOf(server: Server, seriesId: Int, fresh: Boolean = false): List<KavitaVolume> {
        val key = "${server.id}:$seriesId"
        if (!fresh) {
            cacheLock.withLock { volumesCache[key] }?.let { (at, volumes) -> if (now() - at < CACHE_MILLIS) return volumes }
        }
        val volumes = api.volumes(server.resolve("/api/Series/volumes?seriesId=$seriesId"))
        cacheLock.withLock { volumesCache[key] = now() to volumes }
        return volumes
    }

    private suspend fun remember(server: Server, series: KavitaSeries) =
        cacheLock.withLock { seriesCache["${server.id}:${series.id}"] = series }

    private suspend fun seriesOf(server: Server, seriesId: Int): KavitaSeries =
        cacheLock.withLock { seriesCache["${server.id}:$seriesId"] }
            ?: api.seriesById(server.resolve("/api/Series/$seriesId")).also { remember(server, it) }

    /** Library id → Kavita library type, for how a loose chapter is named ("#3" or "Ch. 3"). */
    private suspend fun libraryTypes(server: Server): Map<Int, Int> {
        cacheLock.withLock { libraryTypes[server.id] }?.let { (at, types) -> if (now() - at < CACHE_MILLIS) return types }
        val types = runCatching { api.libraries(server.resolve("/api/Library/libraries")).associate { it.id to it.type } }
            .getOrNull() ?: return emptyMap()
        cacheLock.withLock { libraryTypes[server.id] = now() to types }
        return types
    }

    private suspend fun libraryType(server: Server, libraryId: Int): Int? = libraryTypes(server)[libraryId]

    /**
     * issue #349 — the key Kavita's page images take in their URL: the account's "image-only"
     * key when it has one (it opens images and nothing else), else its own API key.
     */
    private suspend fun imageKey(server: Server): String? {
        cacheLock.withLock { imageKeys[server.id] }?.let { return it }
        val account = runCatching { api.account(server.resolve("/api/Account/refresh-account")) }.getOrNull() ?: return null
        val key = account.authKeys.firstOrNull { it.name.equals("image-only", ignoreCase = true) }?.key?.takeIf { it.isNotBlank() }
            ?: account.apiKey?.takeIf { it.isNotBlank() }
            ?: return null
        cacheLock.withLock { imageKeys[server.id] = key }
        return key
    }

    private suspend inline fun <T> call(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (e: ResponseException) {
        when (val status = e.response.status.value) {
            401 -> Outcome.Failure(DexxiconError.Unauthorized("Kavita refused the sign-in — check the password in Settings"))
            403 -> Outcome.Failure(DexxiconError.Unauthorized("Kavita says this account can't do that (it may need the Download role)"))
            404 -> Outcome.Failure(DexxiconError.NotFound("That's no longer on the server"))
            else -> Outcome.Failure(DexxiconError.Network("Server returned HTTP $status"))
        }
    } catch (e: IOException) {
        Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
    } catch (e: Exception) {
        Outcome.Failure(DexxiconError.Unknown(e.message, e))
    }

    internal companion object {
        const val SERIES_PREFIX = "s:"
        const val COLLECTION_PREFIX = "c:"
        const val READING_LIST_PREFIX = "r:"
        private const val ON_DECK_SIZE = 30
        private const val IN_PROGRESS_SIZE = 50
        private const val ALL = 500
        private const val PARALLEL_LOOKUPS = 6
        private const val CACHE_MILLIS = 5 * 60 * 1000L

        // SeriesFilterField / FilterComparison / SeriesSortField
        const val FIELD_NAME = 1
        const val FIELD_COLLECTION = 7
        const val FIELD_LIBRARY = 19
        const val FIELD_READ_PROGRESS = 20
        const val FIELD_FORMATS = 21
        const val EQUAL = 0
        const val GREATER_THAN = 1
        const val LESS_THAN = 3
        const val CONTAINS = 5
        const val MATCHES = 7
        const val SORT_NAME = 1
        const val SORT_CREATED = 2
        const val SORT_READ_PROGRESS = 7

        /** Kavita's placeholders: a chapter with no number, the volume of loose chapters, specials. */
        const val NO_NUMBER = "-100000"
        const val LOOSE_VOLUME = -100000.0
        const val SPECIAL_VOLUME = 100000.0

        // LibraryType
        private const val LIBRARY_COMIC = 1
        private const val LIBRARY_BOOK = 2
        private const val LIBRARY_COMIC_VINE = 5

        fun statement(field: Int, comparison: Int, value: String) = KavitaFilterStatement(comparison, field, value)

        /** A series-name search; Kavita's "matches" is its contains-text comparison for names. */
        fun nameStatement(query: String?): KavitaFilterStatement? =
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { statement(FIELD_NAME, MATCHES, it) }

        /** The app's formats as Kavita's (1 archive, 3 EPUB, 4 PDF); null when nothing narrows. */
        fun formatStatement(formats: Set<ContentFormat>?): KavitaFilterStatement? {
            val codes = formats.orEmpty().mapNotNull {
                when (it) {
                    ContentFormat.COMIC -> 1
                    ContentFormat.EPUB -> 3
                    ContentFormat.PDF -> 4
                    else -> null
                }
            }.distinct().sorted()
            if (formats == null || codes.isEmpty()) return null
            return statement(FIELD_FORMATS, CONTAINS, codes.joinToString(","))
        }

        fun sortOptions(sort: BookSort): KavitaSortOptions = when (sort) {
            BookSort.RECENT -> KavitaSortOptions(SORT_CREATED, isAscending = false)
            BookSort.TITLE, BookSort.SERIES -> KavitaSortOptions(SORT_NAME, isAscending = true)
        }

        fun formatOf(mangaFormat: Int, extension: String?): ContentFormat = when (mangaFormat) {
            1 -> ContentFormat.COMIC
            3 -> ContentFormat.EPUB
            4 -> ContentFormat.PDF
            else -> when (extension?.lowercase()) {
                "epub" -> ContentFormat.EPUB
                "pdf" -> ContentFormat.PDF
                "cbz", "cbr", "cb7", "zip", "rar" -> ContentFormat.COMIC
                else -> ContentFormat.UNKNOWN
            }
        }

        fun mediaTypeOf(format: ContentFormat, extension: String?): String = when (format) {
            ContentFormat.EPUB -> "application/epub+zip"
            ContentFormat.PDF -> "application/pdf"
            ContentFormat.COMIC -> when (extension?.lowercase()) {
                "cbr", "rar" -> "application/vnd.comicbook-rar"
                "cb7" -> "application/x-cb7"
                else -> "application/vnd.comicbook+zip"
            }
            else -> "application/octet-stream"
        }

        /** The file's extension, lower-case without the dot ("cbz"). */
        fun KavitaChapter.extension(): String? =
            files.firstOrNull()?.extension?.trim()?.removePrefix(".")?.lowercase()?.takeIf { it.isNotBlank() }

        fun KavitaChapter.readingStatus(): ReadingStatus = when {
            pages > 0 && pagesRead >= pages -> ReadingStatus.READ
            pagesRead > 0 -> ReadingStatus.READING
            else -> ReadingStatus.UNREAD
        }

        fun KavitaChapter.writerNames(): List<String> = writers.map { it.name }.filter { it.isNotBlank() }.distinct()

        fun List<KavitaVolume>.chapterCount(): Int = sumOf { it.chapters.size }

        private fun KavitaVolume.isNumbered() = minNumber != LOOSE_VOLUME && minNumber != SPECIAL_VOLUME

        private fun KavitaChapter.hasNumber() = number != null && number != NO_NUMBER

        private fun KavitaChapter.isSpecialIn(volume: KavitaVolume?) = isSpecial || volume?.minNumber == SPECIAL_VOLUME

        /** Kavita's reading order: numbered volumes, then loose chapters, then specials. */
        fun readingGroup(volume: KavitaVolume, chapter: KavitaChapter): Int = when {
            chapter.isSpecialIn(volume) -> 2
            volume.isNumbered() -> 0
            else -> 1
        }

        fun seriesIndexOf(chapter: KavitaChapter, volume: KavitaVolume?): Double? = when {
            chapter.isSpecialIn(volume) -> null
            chapter.hasNumber() -> chapter.minNumber
            volume != null && volume.isNumbered() -> volume.minNumber
            else -> null
        }

        /**
         * How a chapter is called inside its series: "Vol. 3", "Ch. 12", "Vol. 2 Ch. 9", "#4"
         * for a comic issue, or a special's own title.
         */
        fun chapterLabel(chapter: KavitaChapter, volume: KavitaVolume?, libraryType: Int?): String {
            val name = chapter.titleName?.takeIf { it.isNotBlank() && it != NO_NUMBER }
            if (chapter.isSpecialIn(volume)) {
                return name ?: chapter.title?.takeIf { it.isNotBlank() && it != NO_NUMBER }
                    ?: chapter.range?.takeIf { it.isNotBlank() && it != NO_NUMBER } ?: "Special"
            }
            val number = chapter.range?.takeIf { it.isNotBlank() && it != NO_NUMBER } ?: chapter.number?.takeIf { chapter.hasNumber() }
            val volumeName = volume?.takeIf { it.isNumbered() }?.let { v -> v.name?.takeIf { it.isNotBlank() } ?: formatNumber(v.minNumber) }
            val chapterPart = number?.let {
                when (libraryType) {
                    LIBRARY_COMIC, LIBRARY_COMIC_VINE -> "#$it"
                    LIBRARY_BOOK -> "Book $it"
                    else -> "Ch. $it"
                }
            }
            val label = listOfNotNull(volumeName?.let { "Vol. $it" }, chapterPart).joinToString(" ").ifBlank { name ?: "Chapter" }
            return if (name != null && name != number && chapterPart != null) "$label: $name" else label
        }

        /** The book's own title: the series' name for a lone book, else series + chapter label. */
        fun bookTitle(seriesName: String, chapter: KavitaChapter, volume: KavitaVolume?, single: Boolean, libraryType: Int?): String {
            if (single) return seriesName
            val label = chapterLabel(chapter, volume, libraryType)
            return if (chapter.isSpecialIn(volume) || label.contains(seriesName, ignoreCase = true)) label else "$seriesName $label"
        }

        private fun formatNumber(n: Double): String = if (n == kotlin.math.floor(n)) n.toLong().toString() else n.toString()

        /** A local page of an already complete list. */
        fun List<BookSummary>.pageOf(page: Int, pageSize: Int): BookPage {
            val from = (page * pageSize).coerceAtMost(size)
            val to = (from + pageSize).coerceAtMost(size)
            return BookPage(subList(from, to), page, hasMore = to < size, total = size)
        }
    }
}
