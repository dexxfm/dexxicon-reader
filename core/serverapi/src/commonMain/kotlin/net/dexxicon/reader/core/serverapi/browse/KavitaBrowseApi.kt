package net.dexxicon.reader.core.serverapi.browse

import io.ktor.client.statement.bodyAsText
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * issue #328 — Kavita's REST API (a JWT from `POST /api/Account/login`, which the auth plugin
 * sends as a Bearer for a saved server; covers and file downloads take it too).
 *
 * Kavita keeps series → volumes → chapters → files. A chapter is what Kavita reads, tracks
 * and downloads as one unit, so it is the app's book.
 *
 *   GET  /api/Library/libraries                          libraries (type: 0 manga, 1 comic, 2 book…)
 *   POST /api/Series/all-v2?PageNumber=&PageSize=        series matching a filter (library, name,
 *        formats, collection…), paged 1-based; the totals come back in a `Pagination` header
 *   GET  /api/Series/volumes?seriesId=                   a series' volumes, each with its chapters
 *   GET  /api/Series/metadata?seriesId=                  summary, genres, writers, publishers
 *   GET  /api/Chapter?chapterId=                         one chapter (its volume, files, metadata)
 *   GET  /api/Search/series-for-chapter?chapterId=       the chapter's series (and library)
 *   POST /api/Series/on-deck                             series in progress
 *   GET  /api/Reader/continue-point?seriesId=            the chapter to carry on with
 *   GET  /api/Collection                                 collections of series
 *   POST /api/ReadingList/lists, GET /api/ReadingList/items?readingListId=   reading lists of chapters
 * Covers: /api/Image/series-cover?seriesId=, /api/Image/chapter-cover?chapterId=. The file is
 * /api/Download/chapter?chapterId= (the user needs Kavita's Download role); it answers GET
 * only — a HEAD is a 404 — so readers fetch it whole. `/api/Reader/chapter-info` is avoided: it unpacks the whole archive first.
 */
class KavitaBrowseApi(private val client: HttpClient) {

    suspend fun libraries(url: String): List<KavitaLibrary> = client.get(url).body()

    /** [url] carries `PageNumber` and `PageSize`. */
    suspend fun seriesPage(url: String, filter: KavitaSeriesFilter): KavitaPage<KavitaSeries> {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(filter)
        }
        val items: List<KavitaSeries> = response.body()
        val pagination = response.headers["Pagination"]
            ?.let { runCatching { lenient.decodeFromString<KavitaPagination>(it) }.getOrNull() }
        return KavitaPage(items, pagination)
    }

    suspend fun volumes(url: String): List<KavitaVolume> = client.get(url).body()

    /** `GET /api/Series/{seriesId}`. */
    suspend fun seriesById(url: String): KavitaSeries = client.get(url).body()

    suspend fun seriesMetadata(url: String): KavitaSeriesMetadata = client.get(url).body()

    suspend fun chapter(url: String): KavitaChapter = client.get(url).body()

    suspend fun seriesForChapter(url: String): KavitaSeries = client.get(url).body()

    suspend fun onDeck(url: String): List<KavitaSeries> =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(EMPTY_OBJECT)
        }.body()

    suspend fun continuePoint(url: String): KavitaChapter = client.get(url).body()

    suspend fun collections(url: String): List<KavitaCollection> = client.get(url).body()

    suspend fun readingLists(url: String): List<KavitaReadingList> =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(EMPTY_OBJECT)
        }.body()

    suspend fun readingListItems(url: String): List<KavitaReadingListItem> = client.get(url).body()

    /**
     * `GET /api/Admin/exists` — answered without signing in, with a bare `true`/`false`. Add
     * server uses it to tell a Kavita that refused the password from a server that isn't Kavita.
     */
    suspend fun adminExists(url: String): Boolean? =
        client.get(url).bodyAsText().trim().toBooleanStrictOrNull()

    /** The signed-in account (`/api/Account/refresh-account`). */
    suspend fun account(url: String): KavitaUser = client.get(url).body()

    private companion object {
        val lenient = Json { ignoreUnknownKeys = true }
        val EMPTY_OBJECT = kotlinx.serialization.json.JsonObject(emptyMap())
    }
}

/** One page of a series list, with Kavita's `Pagination` header when it sent one. */
data class KavitaPage<T>(val items: List<T>, val pagination: KavitaPagination?) {
    /** More after this page: per the header, else judged by a full page. */
    fun hasMore(pageSize: Int): Boolean =
        pagination?.let { it.currentPage < it.totalPages } ?: (items.size >= pageSize)
}

@Serializable
data class KavitaPagination(
    val currentPage: Int = 1,
    val itemsPerPage: Int = 0,
    val totalItems: Int = 0,
    val totalPages: Int = 0,
)

/**
 * Kavita's `SeriesFilterV2Dto`: every statement must hold (`combination` 1 = and). No defaults
 * on purpose — the shared JSON config leaves default values out, and a missing `combination`
 * is Kavita's 0, "any statement", which would widen a library's list to every library.
 */
@Serializable
data class KavitaSeriesFilter(
    val statements: List<KavitaFilterStatement>,
    val combination: Int,
    val sortOptions: KavitaSortOptions,
    val limitTo: Int,
) {
    companion object {
        fun allOf(statements: List<KavitaFilterStatement>, sort: KavitaSortOptions) =
            KavitaSeriesFilter(statements, combination = 1, sortOptions = sort, limitTo = 0)
    }
}

@Serializable
data class KavitaFilterStatement(
    /** `FilterComparison`: 0 equal, 1 greater than, 3 less than, 5 contains, 7 matches… */
    val comparison: Int,
    /** `SeriesFilterField`: 1 name, 7 collection, 19 library, 20 read progress, 21 formats… */
    val field: Int,
    val value: String,
)

@Serializable
data class KavitaSortOptions(
    /** `SeriesSortField`: 1 sort name, 2 created, 4 last chapter added, 7 read progress (last read). */
    val sortField: Int,
    val isAscending: Boolean,
)

@Serializable
data class KavitaLibrary(
    val id: Int,
    val name: String,
    /** `LibraryType`: 0 manga, 1 comic, 2 book, 3 image, 4 light novel, 5 comic (ComicVine). */
    val type: Int = 0,
)

@Serializable
data class KavitaSeries(
    val id: Int,
    val name: String,
    val sortName: String? = null,
    val localizedName: String? = null,
    /** `MangaFormat`: 0 image, 1 archive, 2 unknown, 3 EPUB, 4 PDF. */
    val format: Int = 2,
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val libraryId: Int = 0,
    val libraryName: String? = null,
    val created: String? = null,
    val latestReadDate: String? = null,
)

@Serializable
data class KavitaVolume(
    val id: Int,
    /** Display name: the volume number as text ("3"), or a sentinel for loose chapters / specials. */
    val name: String? = null,
    val minNumber: Double = 0.0,
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val seriesId: Int = 0,
    val chapters: List<KavitaChapter> = emptyList(),
)

@Serializable
data class KavitaChapter(
    val id: Int,
    val volumeId: Int = 0,
    /** The chapter number as text; Kavita's "-100000" means none (a volume-level file). */
    val number: String? = null,
    val range: String? = null,
    val minNumber: Double = 0.0,
    val sortOrder: Double = 0.0,
    val isSpecial: Boolean = false,
    val title: String? = null,
    val titleName: String? = null,
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val format: Int = 2,
    val files: List<KavitaFile> = emptyList(),
    val summary: String? = null,
    val isbn: String? = null,
    val language: String? = null,
    val releaseDate: String? = null,
    val created: String? = null,
    val lastReadingProgressUtc: String? = null,
    val writers: List<KavitaPerson> = emptyList(),
    val publishers: List<KavitaPerson> = emptyList(),
    val genres: List<KavitaTag> = emptyList(),
    val tags: List<KavitaTag> = emptyList(),
)

@Serializable
data class KavitaFile(
    val id: Int = 0,
    val filePath: String? = null,
    val pages: Int = 0,
    val bytes: Long = 0,
    val format: Int = 2,
    val extension: String? = null,
)

@Serializable
data class KavitaPerson(val name: String = "")

@Serializable
data class KavitaTag(val title: String = "")

@Serializable
data class KavitaSeriesMetadata(
    val summary: String? = null,
    val language: String? = null,
    val releaseYear: Int = 0,
    val writers: List<KavitaPerson> = emptyList(),
    val publishers: List<KavitaPerson> = emptyList(),
    val genres: List<KavitaTag> = emptyList(),
    val tags: List<KavitaTag> = emptyList(),
)

@Serializable
data class KavitaCollection(
    val id: Int,
    val title: String,
    val itemCount: Int = 0,
)

@Serializable
data class KavitaReadingList(
    val id: Int,
    val title: String,
    val itemCount: Int = 0,
)

@Serializable
data class KavitaReadingListItem(
    val id: Int = 0,
    val order: Int = 0,
    val chapterId: Int,
    val seriesId: Int = 0,
    val seriesName: String? = null,
    val seriesFormat: Int = 2,
    val volumeId: Int = 0,
    val libraryId: Int = 0,
    val libraryType: Int = 0,
    val title: String? = null,
    val chapterNumber: String? = null,
    val volumeNumber: String? = null,
    val pagesRead: Int = 0,
    val pagesTotal: Int = 0,
    val isSpecial: Boolean = false,
)

@Serializable
data class KavitaUser(
    val username: String? = null,
    val email: String? = null,
    /** The account's own key; page images (`/api/Reader/image`) need one in their URL. */
    val apiKey: String? = null,
    /** Named keys, e.g. "opds" and "image-only" (good for images and nothing else). */
    val authKeys: List<KavitaAuthKey> = emptyList(),
)

@Serializable
data class KavitaAuthKey(val name: String? = null, val key: String? = null)
