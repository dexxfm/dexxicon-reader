package net.dexxicon.reader.core.serverapi.browse

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BookOrbit native browse endpoints (JWT bearer). Discovered from the web client:
 *   GET  /api/v1/libraries                       → [BookOrbitLibrary]
 *   POST /api/v1/books/query                     → BookOrbitBookPage  (whole catalogue)
 *   POST /api/v1/libraries/{id}/books            → BookOrbitBookPage  (one library)
 *   GET  /api/v1/books/{id}                      → BookOrbitBook      (detail)
 * Covers:    GET /api/v1/books/{id}/cover        (image, bearer header)
 * Streaming: GET /api/v1/books/files/{fileId}/serve     (range-capable)
 * Download:  GET /api/v1/books/files/{fileId}/download  (whole file)
 */
class BookOrbitBrowseApi(private val client: HttpClient) {

    suspend fun libraries(url: String): List<BookOrbitLibrary> = client.get(url).body()

    suspend fun booksQuery(url: String, body: BookOrbitQuery): BookOrbitBookPage =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    suspend fun book(url: String): BookOrbitBook = client.get(url).body()

    /** `GET /api/v1/dashboard/scrollers/{continue-reading|continue-listening}` → book cards. */
    suspend fun dashboardScroller(url: String): List<BookOrbitBook> = client.get(url).body()
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
    val files: List<BookOrbitFile> = emptyList(),
    val audioMetadata: BookOrbitAudioMeta? = null,
) {
    /** The file the readers/downloader should use. */
    val primaryFile: BookOrbitFile?
        get() = files.firstOrNull { it.role.equals("primary", ignoreCase = true) }
            ?: files.firstOrNull()
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
