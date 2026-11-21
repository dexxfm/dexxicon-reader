package net.dexxicon.reader.core.serverapi.browse

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * issue #327 — Komga's REST API (HTTP Basic, which the auth plugin adds for a saved server).
 *
 *   GET  /api/v1/libraries                      libraries
 *   POST /api/v1/books/list?page=&size=&sort=   books matching a search condition — library,
 *        series, media profile (EPUB / PDF / DIVINA), read status — plus `fullTextSearch`.
 *        The GET `/api/v1/books` ignores a media-profile filter, so every filtered list uses this.
 *   GET  /api/v1/books/{id}                     one book, with its own read progress
 *   GET  /api/v1/books/ondeck                   the next unread book of each series in progress
 *   GET  /api/v1/series?search=&page=&size=&sort=   series (metadata.titleSort)
 *   GET  /api/v1/series/{id}                    one series: publisher, language, genres
 *   GET  /api/v1/collections, /api/v1/collections/{id}   collections of series
 *   GET  /api/v1/readlists, /api/v1/readlists/{id}/books  read lists of books, in list order
 *   GET  /api/v2/users/me                       the signed-in account
 * Covers: /api/v1/books/{id}/thumbnail and /api/v1/series/{id}/thumbnail. The book file is
 * /api/v1/books/{id}/file, which ignores HTTP range requests (it always sends the whole file).
 * Sort fields: createdDate, metadata.title, metadata.numberSort, series, readProgress.readDate.
 */
class KomgaBrowseApi(private val client: HttpClient) {

    suspend fun libraries(url: String): List<KomgaLibrary> = client.get(url).body()

    /** [body] is `{"condition": {...}, "fullTextSearch": "..."}`; either may be absent. */
    suspend fun bookList(url: String, body: JsonObject): KomgaPage<KomgaBook> =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    suspend fun bookPage(url: String): KomgaPage<KomgaBook> = client.get(url).body()

    suspend fun book(url: String): KomgaBook = client.get(url).body()

    suspend fun seriesPage(url: String): KomgaPage<KomgaSeries> = client.get(url).body()

    suspend fun series(url: String): KomgaSeries = client.get(url).body()

    suspend fun collections(url: String): KomgaPage<KomgaCollection> = client.get(url).body()

    suspend fun collection(url: String): KomgaCollection = client.get(url).body()

    suspend fun readLists(url: String): KomgaPage<KomgaReadList> = client.get(url).body()

    /**
     * The signed-in account. [authorization] is sent as-is when given: Add server checks an
     * address and password before anything is saved, so the auth plugin has no server to add
     * a header for yet.
     */
    suspend fun me(url: String, authorization: String? = null): KomgaUser =
        client.get(url) { authorization?.let { header("Authorization", it) } }.body()
}

/** Spring's page wrapper, as Komga sends it. */
@Serializable
data class KomgaPage<T>(
    val content: List<T> = emptyList(),
    val number: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val last: Boolean = true,
)

@Serializable
data class KomgaLibrary(
    val id: String,
    val name: String,
)

@Serializable
data class KomgaBook(
    val id: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val libraryId: String? = null,
    val name: String = "",
    /** The file's name on disk, which carries its extension. */
    val url: String? = null,
    val number: Int? = null,
    val created: String? = null,
    val sizeBytes: Long? = null,
    val media: KomgaMedia = KomgaMedia(),
    val metadata: KomgaBookMetadata = KomgaBookMetadata(),
    val readProgress: KomgaReadProgress? = null,
    val fileHash: String? = null,
) {
    val title: String get() = metadata.title?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class KomgaMedia(
    val status: String? = null,
    val mediaType: String? = null,
    val pagesCount: Int = 0,
    /** EPUB, PDF or DIVINA (comics: CBZ / CBR / image folders). */
    val mediaProfile: String? = null,
    val epubIsKepub: Boolean = false,
)

@Serializable
data class KomgaBookMetadata(
    val title: String? = null,
    val summary: String? = null,
    val number: String? = null,
    val numberSort: Double? = null,
    val releaseDate: String? = null,
    val authors: List<KomgaAuthor> = emptyList(),
    val tags: List<String> = emptyList(),
    val isbn: String? = null,
)

@Serializable
data class KomgaAuthor(
    val name: String,
    /** writer, penciller, inker, colorist, letterer, cover, editor, translator… */
    val role: String? = null,
)

/** The current user's progress: [page] is 1-based; [completed] once the last page is read. */
@Serializable
data class KomgaReadProgress(
    val page: Int = 0,
    val completed: Boolean = false,
    val readDate: String? = null,
    val lastModified: String? = null,
)

@Serializable
data class KomgaSeries(
    val id: String,
    val libraryId: String? = null,
    val name: String = "",
    val booksCount: Int? = null,
    val metadata: KomgaSeriesMetadata = KomgaSeriesMetadata(),
    val booksMetadata: KomgaBooksMetadata = KomgaBooksMetadata(),
) {
    val title: String get() = metadata.title?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class KomgaSeriesMetadata(
    val title: String? = null,
    val titleSort: String? = null,
    val summary: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class KomgaBooksMetadata(
    val authors: List<KomgaAuthor> = emptyList(),
    val summary: String? = null,
    val releaseDate: String? = null,
)

/** A collection groups series. */
@Serializable
data class KomgaCollection(
    val id: String,
    val name: String,
    val seriesIds: List<String> = emptyList(),
)

/** A read list orders books. */
@Serializable
data class KomgaReadList(
    val id: String,
    val name: String,
    val bookIds: List<String> = emptyList(),
)

@Serializable
data class KomgaUser(
    val id: String? = null,
    val email: String? = null,
    val roles: List<String> = emptyList(),
)
