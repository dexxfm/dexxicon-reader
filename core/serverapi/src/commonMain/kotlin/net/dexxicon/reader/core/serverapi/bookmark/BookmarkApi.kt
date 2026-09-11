package net.dexxicon.reader.core.serverapi.bookmark

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/**
 * EPUB bookmarks (JWT bearer). Both server families have full CRUD:
 *
 *   BookOrbit  GET/POST /api/v1/books/{bookId}/bookmarks
 *              DELETE   /api/v1/books/{bookId}/bookmarks/{bookmarkId}
 *   Grimmory   GET      /api/v1/bookmarks/book/{bookId}
 *              POST     /api/v1/bookmarks
 *              DELETE   /api/v1/bookmarks/{bookmarkId}
 *
 * Both store an EPUB CFI in `cfi` and a chapter label in `title`. BookOrbit takes the
 * book id in the URL; Grimmory takes it in the body. Grimmory returns 409 on a duplicate
 * `cfi` — callers catch Ktor's `ResponseException` for that, same as any other HTTP error.
 */
class BookmarkApi(private val client: HttpClient) {

    suspend fun list(url: String): List<BookmarkDto> = client.get(url).body()

    suspend fun createBookOrbit(url: String, body: BookOrbitBookmarkBody): BookmarkDto =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    suspend fun createGrimmory(url: String, body: GrimmoryBookmarkBody): BookmarkDto =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    /** Callers never inspected the Retrofit `Response<Unit>` this returned either — a failed
     * delete doesn't stop the local-side cleanup that follows it. */
    suspend fun delete(url: String) {
        client.delete(url) { expectSuccess = false }
    }
}

@Serializable
data class BookmarkDto(
    val id: Long? = null,
    val bookId: Long? = null,
    val cfi: String? = null,
    val title: String? = null,
)

@Serializable
data class BookOrbitBookmarkBody(
    val cfi: String,
    val title: String,
)

@Serializable
data class GrimmoryBookmarkBody(
    val bookId: Long,
    val cfi: String,
    val title: String,
)
