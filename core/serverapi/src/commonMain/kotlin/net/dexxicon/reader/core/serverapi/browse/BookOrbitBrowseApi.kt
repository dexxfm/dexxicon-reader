package net.dexxicon.reader.core.serverapi.browse

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * BookOrbit native browse endpoints (JWT bearer). Discovered from the web client:
 *   GET  /api/v1/libraries                       → [BookOrbitLibrary]
 *   POST /api/v1/books/query                     → BookOrbitBookPage  (whole catalogue)
 *   POST /api/v1/libraries/{id}/books            → BookOrbitBookPage  (one library)
 *   GET  /api/v1/books/{id}                      → BookOrbitBook      (detail)
 * Covers:    GET /api/v1/books/{id}/cover        (image, bearer header)
 * Streaming: GET /api/v1/books/files/{fileId}/serve     (range-capable, non-audio only —
 *            see [audiobookManifest] below for audio)
 * Download:  GET /api/v1/books/files/{fileId}/download  (whole file)
 *
 * Audiobooks (server 2.10.0+, issue #192): `files/{fileId}/serve` now 404s for audio
 * formats — streaming moved to a dedicated controller keyed by `assetId`, not `fileId`:
 *   GET /api/v1/audiobooks/{bookId}/manifest                    → [BookOrbitAudiobookManifest]
 *   GET /api/v1/audiobooks/{bookId}/assets/{assetId}/content    → the stream (range-capable)
 * Servers older than 2.10 don't have this controller at all — [audiobookManifestOrNull] is how
 * callers detect that and fall back to the pre-2.10 `files/{fileId}/serve` /
 * `books/{bookId}/audio-progress` routes instead.
 */
class BookOrbitBrowseApi(private val client: HttpClient) {
    /** Decodes [dashboardScroller]'s cards — same settings as the HTTP client's own JSON. */
    private val scrollerJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }


    suspend fun libraries(url: String): List<BookOrbitLibrary> = client.get(url).body()

    suspend fun booksQuery(url: String, body: BookOrbitQuery): BookOrbitBookPage =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    suspend fun book(url: String): BookOrbitBook = client.get(url).body()

    /** `GET /api/v1/series/{id}/books` (issue #256) — a `BooksPage` plus `seriesInfo`, which
     *  [BookOrbitBookPage] simply ignores. */
    suspend fun booksPage(url: String): BookOrbitBookPage = client.get(url).body()

    /** `GET /api/v1/series?q=&page=&size=&sort=name` (issue #256). */
    suspend fun series(url: String): BookOrbitSeriesPage = client.get(url).body()

    /** `GET /api/v1/collections` (issue #254) — book *and* podcast collections; callers keep
     *  [BookOrbitCollection.isBooks] ones only. */
    suspend fun collections(url: String): List<BookOrbitCollection> = client.get(url).body()

    /** `GET /api/v1/smart-scopes` (issue #254) — same book/podcast split as [collections]. */
    suspend fun smartScopes(url: String): List<BookOrbitSmartScope> = client.get(url).body()

    /**
     * `GET /api/v1/dashboard/scrollers/{continue-reading|continue-listening|want-to-read}` →
     * book cards. issue #283 — BookOrbit 3.0 wraps them in a `DashboardScrollerResponse`
     * (`{ books, total }`); older servers return the bare array. Both are accepted — the bare
     * array alone made every sync on a 3.0 server fail ("didn't respond properly" on Home).
     */
    suspend fun dashboardScroller(url: String): List<BookOrbitBook> {
        val books = when (val body: JsonElement = client.get(url).body()) {
            is JsonArray -> body
            is JsonObject -> body["books"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return scrollerJson.decodeFromJsonElement(ListSerializer(BookOrbitBook.serializer()), books)
    }

    /** Null on a server older than 2.10 (the whole `/audiobooks` controller 404s — there's no
     * per-endpoint way to tell "no manifest for this book" apart from "no such controller",
     * but a book already confirmed as an audiobook always has one on a 2.10+ server, so 404
     * unambiguously means the pre-2.10 API is what's there instead). Any other failure
     * (network, 401/403, a genuine 5xx) rethrows rather than being mistaken for that. */
    suspend fun audiobookManifestOrNull(url: String): BookOrbitAudiobookManifest? = try {
        client.get(url).body()
    } catch (e: ResponseException) {
        if (e.response.status.value == 404) null else throw e
    }
}

@Serializable
data class BookOrbitLibrary(
    val id: Long,
    val name: String,
    val bookCount: Int? = null,
)

@Serializable
data class BookOrbitQuery(
    val sort: List<BookOrbitSort> = emptyList(),
    val q: String? = null,
    val pagination: BookOrbitPagination,
)

@Serializable
data class BookOrbitSort(val field: String, val dir: String)

@Serializable
data class BookOrbitPagination(val page: Int, val size: Int)

@Serializable
data class BookOrbitBookPage(
    val items: List<BookOrbitBook> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val size: Int = 0,
)

@Serializable
data class BookOrbitBook(
    val id: Long,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val authors: List<String> = emptyList(),
    @Serializable(with = FlexibleStringListSerializer::class)
    val narrators: List<String> = emptyList(),
    @SerialName("seriesName") val seriesName: String? = null,
    @SerialName("seriesIndex") val seriesIndex: String? = null,
    /** issue #256 — what `/api/v1/series/{id}/books` is keyed by. */
    val seriesId: Long? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val publishedDate: String? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val isbn13: String? = null,
    val isbn10: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val hasCover: Boolean = false,
    val libraryId: Long? = null,
    val libraryName: String? = null,
    val readStatus: BookOrbitReadStatus? = null,
    /** Overall progression 0–1, present on dashboard scroller cards. */
    val readingProgress: Double? = null,
    /** The current user's own 1–5 rating (issue #264) — distinct from `communityRatings`
     *  (external providers' aggregate scores), which this app doesn't surface. */
    val rating: Int? = null,
    val files: List<BookOrbitFile> = emptyList(),
    val audioMetadata: BookOrbitAudioMeta? = null,
) {
    /** The file the readers/downloader should use. */
    val primaryFile: BookOrbitFile?
        get() = files.firstOrNull { it.role.equals("primary", ignoreCase = true) }
            ?: files.firstOrNull()
}

/** `SeriesPage` (server `packages/types/src/series.ts`), trimmed to what the Series tab shows. */
@Serializable
data class BookOrbitSeriesPage(
    val items: List<BookOrbitSeries> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val size: Int = 0,
)

@Serializable
data class BookOrbitSeries(
    val id: Long,
    val name: String,
    val bookCount: Int? = null,
    val authors: List<String> = emptyList(),
    /** Books whose covers represent the series, first volume first. */
    val coverBookIds: List<Long> = emptyList(),
)

/** `Collection` (server `packages/types/src/collection.ts`). */
@Serializable
data class BookOrbitCollection(
    val id: Long,
    val name: String,
    val mediaType: String? = null,
    val bookCount: Int? = null,
) {
    val isBooks: Boolean get() = mediaType == null || mediaType.equals("books", ignoreCase = true)
}

/** `SmartScope` (server `packages/types/src/smart-scope.ts`). `bookCount` is null when the
 *  scope's saved filter failed to validate server-side. */
@Serializable
data class BookOrbitSmartScope(
    val id: Long,
    val name: String,
    val mediaType: String? = null,
    val bookCount: Int? = null,
) {
    val isBooks: Boolean get() = mediaType == null || mediaType.equals("books", ignoreCase = true)
}

@Serializable
data class BookOrbitAudioMeta(
    val durationSeconds: Long? = null,
    val chapters: List<BookOrbitChapter> = emptyList(),
)

@Serializable
data class BookOrbitChapter(
    val title: String? = null,
    val startMs: Long? = null,
)

@Serializable
data class BookOrbitReadStatus(val status: String? = null)

@Serializable
data class BookOrbitFile(
    val id: Long,
    val format: String? = null,
    val role: String? = null,
    val sizeBytes: Long? = null,
    val filename: String? = null,
    val durationSeconds: Long? = null,
)

/** Only the fields Dexxicon needs from `AudiobookManifest` (server `packages/types`) —
 * `assets` to pick a streaming `assetId`, `revision` since every playback-state write must
 * echo it back as `manifestRevision`. `ignoreUnknownKeys` drops the rest (book/chapters/etc). */
@Serializable
data class BookOrbitAudiobookManifest(
    val revision: String,
    val assets: List<BookOrbitAudiobookAsset> = emptyList(),
)

@Serializable
data class BookOrbitAudiobookAsset(
    val assetId: String,
    val sequence: Int = 0,
)
