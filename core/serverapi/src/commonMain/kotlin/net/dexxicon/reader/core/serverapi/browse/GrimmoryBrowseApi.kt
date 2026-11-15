package net.dexxicon.reader.core.serverapi.browse

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import net.dexxicon.reader.core.serverapi.progress.GrimmoryAudiobookProgress
import net.dexxicon.reader.core.serverapi.progress.GrimmoryEpubProgress
import net.dexxicon.reader.core.serverapi.progress.GrimmoryPagedProgress

/**
 * BookLore / Grimmory browse endpoints (JWT bearer). Only Grimmory's supported web API — issue
 * #335: its `/api/v1/app/…` "mobile" API is deprecated and being removed (grimmory#2812), and
 * `app/libraries` already 500s for non-admin users (grimmory#2805).
 *
 *   GET /api/v1/libraries                       libraries
 *   GET /api/v1/shelves                         hand-made shelves (own + public)
 *   GET /api/magic-shelves                      rule-based shelves — note: no `/v1`
 *   GET /api/v1/books/page?page=&size=&sort=&query=&facet=&facet_logic=
 *       every book list: facets `library:{id}`, `shelf:{id}`, `shelf:magic:{id}`,
 *       `series:{name}`, `file_type:{EPUB|PDF|CBX|AUDIOBOOK|…}`, `read_status:{…}`. Always send
 *       `sort` (without sort/facet/query the server falls back to a legacy shape), and
 *       `facet_logic=or` when one facet has several values (3.5.0 defaults to AND). Max size 100.
 *   GET /api/v1/books/{id}[?withDescription=true]   one book, with its files and progress
 *   GET /api/v1/books/facets/{name}?search=&page=&size=   one facet's values, paged — unreleased
 *       Grimmory (after 3.5.0), whose `/books/facets` lists facets without their values.
 *   GET /api/v1/books/facets[?query=]           3.5.0: every facet with up to 100 values each.
 * Covers: /api/v1/media/book/{id}/cover (or /audiobook-cover), accepting the bearer header.
 */
class GrimmoryBrowseApi(private val client: HttpClient) {

    suspend fun libraries(url: String): List<GrimmoryLibrary> = client.get(url).body()

    suspend fun shelves(url: String): List<GrimmoryShelf> = client.get(url).body()

    suspend fun magicShelves(url: String): List<GrimmoryShelf> = client.get(url).body()

    suspend fun booksPage(url: String): GrimmoryBookPage = client.get(url).body()

    suspend fun book(url: String): GrimmoryBook = client.get(url).body()

    suspend fun facets(url: String): GrimmoryFacetsResponse = client.get(url).body()

    /**
     * `/api/v1/books/facets/{name}` — null where the server predates it. Grimmory 3.5.0
     * doesn't 404 an unknown path: its web app's catch-all serves `index.html` with a 200,
     * even under `/api`, so anything that isn't JSON counts as "not supported" too.
     */
    suspend fun facetOrNull(url: String): GrimmoryFacetsResponse? = try {
        val response = client.get(url)
        if (response.contentType()?.match(ContentType.Application.Json) == true) response.body() else null
    } catch (e: ResponseException) {
        if (e.response.status.value == 404) null else throw e
    }
}

/** `Shelf` / `MagicShelf` — magic shelves carry no `bookCount`. */
@Serializable
data class GrimmoryShelf(
    val id: Long,
    val name: String,
    val bookCount: Int? = null,
)

/** `FacetGroupsResponse` — `/books/facets` and (unreleased Grimmory) `/books/facets/{name}`. */
@Serializable
data class GrimmoryFacetsResponse(
    val facets: List<GrimmoryFacet> = emptyList(),
    /** Paged single-facet responses add a `next` link while more values follow. */
    val links: List<GrimmoryLink> = emptyList(),
) {
    val hasNext: Boolean get() = links.any { "next" in it.rel }
}

@Serializable
data class GrimmoryLink(
    @Serializable(with = RelListSerializer::class)
    val rel: List<String> = emptyList(),
    val href: String? = null,
)

/** Grimmory's `RelSerializer` writes a single rel as a plain string and several as an array. */
internal object RelListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

@Serializable
data class GrimmoryFacet(
    val metadata: GrimmoryFacetMeta = GrimmoryFacetMeta(),
    val links: List<GrimmoryFacetLink> = emptyList(),
)

@Serializable
data class GrimmoryFacetMeta(
    val key: String? = null,
    val title: String? = null,
)

@Serializable
data class GrimmoryFacetLink(
    val value: String? = null,
    val title: String? = null,
    val properties: GrimmoryFacetProps = GrimmoryFacetProps(),
)

@Serializable
data class GrimmoryFacetProps(
    val numberOfItems: Int? = null,
)

@Serializable
data class GrimmoryLibrary(
    val id: Long,
    val name: String,
)

@Serializable
data class GrimmoryBookPage(
    val content: List<GrimmoryBook> = emptyList(),
    val page: GrimmoryPageMeta? = null,
    // legacy (no sort/query) shape
    val totalElements: Long? = null,
    val totalPages: Int? = null,
    val number: Int = 0,
    val last: Boolean? = null,
) {
    val effectiveNumber: Int get() = page?.number ?: number
    val effectiveTotalPages: Int? get() = page?.totalPages ?: totalPages
    val effectiveTotal: Long? get() = page?.totalElements ?: totalElements
}

@Serializable
data class GrimmoryPageMeta(
    val number: Int = 0,
    val size: Int = 0,
    val totalElements: Long? = null,
    val totalPages: Int? = null,
)

@Serializable
data class GrimmoryBook(
    val id: Long,
    val libraryId: Long? = null,
    val libraryName: String? = null,
    val readStatus: String? = null,
    val lastReadTime: String? = null,
    val metadata: GrimmoryMetadata = GrimmoryMetadata(),
    val primaryFile: GrimmoryFile? = null,
    /** The book's other files (another format of the same book), each with its own id. */
    val alternativeFormats: List<GrimmoryFile> = emptyList(),
    /** The current user's own rating (issue #264), 1–5 on the wire despite the `Double` type —
     *  a top-level field on the book itself (confirmed against a live server), *not* nested
     *  under [metadata] despite every other rating-ish field living there. Distinct from the
     *  separate per-provider fields (`goodreadsRating`, `hardcoverRating`, etc. — all inside
     *  `metadata`), which this app doesn't surface. The write side's `PersonalRatingUpdateRequest`
     *  body field is a differently-named `rating`, see [net.dexxicon.reader.core.serverapi
     *  .progress.GrimmoryRatingUpdate]. */
    val personalRating: Double? = null,
    // issue #335 — the current user's progress, per format, 0–100 (on list rows and detail).
    val epubProgress: GrimmoryEpubProgress? = null,
    val pdfProgress: GrimmoryPagedProgress? = null,
    val cbxProgress: GrimmoryPagedProgress? = null,
    val audiobookProgress: GrimmoryAudiobookProgress? = null,
) {
    /** The primary file first, then the other formats. */
    val files: List<GrimmoryFile> get() = listOfNotNull(primaryFile) + alternativeFormats

    /** The most advanced format progress, 0–100 — what a Continue shelf shows. */
    val readPercent: Double?
        get() = listOfNotNull(
            epubProgress?.percentage,
            pdfProgress?.percentage,
            cbxProgress?.percentage,
            audiobookProgress?.percentage,
        ).maxOrNull()
}

@Serializable
data class GrimmoryMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val seriesName: String? = null,
    val seriesNumber: Double? = null,
    val isbn13: String? = null,
    val isbn10: String? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val narrator: String? = null,
    val authors: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val audiobookMetadata: GrimmoryAudiobookMeta? = null,
)

@Serializable
data class GrimmoryAudiobookMeta(
    val durationSeconds: Long? = null,
    val chapters: List<GrimmoryChapter> = emptyList(),
)

@Serializable
data class GrimmoryChapter(
    val title: String? = null,
    val startTimeMs: Long? = null,
)

@Serializable
data class GrimmoryFile(
    /** The book file id — what progress writes are keyed by. */
    val id: Long? = null,
    val bookType: String? = null,
    val extension: String? = null,
    val archiveType: String? = null,
    val fileSizeKb: Long? = null,
)
