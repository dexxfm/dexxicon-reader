package net.dexxicon.reader.core.serverapi.browse

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * BookLore / Grimmory native browse endpoints (JWT bearer):
 *   GET /api/v1/libraries
 *   GET /api/v1/books/page?page=&size=&sort=&query=
 *   GET /api/v1/books/{id}
 * Covers: /api/v1/media/book/{id}/cover  (accepts the bearer header).
 */
interface GrimmoryBrowseApi {

    @GET
    suspend fun libraries(@Url url: String): List<GrimmoryLibrary>

    @GET
    suspend fun booksPage(@Url url: String): GrimmoryBookPage

    @GET
    suspend fun book(@Url url: String): GrimmoryBook

    @GET
    suspend fun facets(@Url url: String): GrimmoryFacetsResponse
}

@Serializable
data class GrimmoryFacetsResponse(
    val facets: List<GrimmoryFacet> = emptyList(),
)

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
    val metadata: GrimmoryMetadata = GrimmoryMetadata(),
    val primaryFile: GrimmoryFile? = null,
)

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
    val bookType: String? = null,
    val extension: String? = null,
    val archiveType: String? = null,
    val fileSizeKb: Long? = null,
)
