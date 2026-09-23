package net.dexxicon.reader.core.serverapi.annotation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/**
 * BookLore / Grimmory annotation CRUD (JWT bearer):
 *   GET    /api/v1/annotations/book/{bookId}
 *   POST   /api/v1/annotations
 *   PUT    /api/v1/annotations/{id}
 *   DELETE /api/v1/annotations/{id}
 * BookOrbit exposes only a read view: `GET /api/v1/annotations?bookId={id}` → { items: [...] }.
 */
class AnnotationApi(private val client: HttpClient) {

    suspend fun listForBook(url: String): List<AnnotationDto> = client.get(url).body()

    suspend fun listBookOrbit(url: String): BookOrbitAnnotationsPage = client.get(url).body()

    suspend fun create(url: String, body: CreateAnnotationDto): AnnotationDto =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    suspend fun update(url: String, body: UpdateAnnotationDto): AnnotationDto =
        client.put(url) {
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
data class AnnotationDto(
    val id: Long? = null,
    val bookId: Long? = null,
    val cfi: String? = null,
    val text: String? = null,
    val color: String? = null,
    val style: String? = null,
    val note: String? = null,
    val chapterTitle: String? = null,
    /** issue #266 — a `LocalDateTime` with no zone, e.g. `2026-09-20T14:03:11`. */
    val createdAt: String? = null,
)

@Serializable
data class CreateAnnotationDto(
    val bookId: Long,
    val cfi: String,
    val text: String,
    val color: String,
    val style: String = "highlight",
    val note: String? = null,
    val chapterTitle: String? = null,
)

@Serializable
data class UpdateAnnotationDto(
    val color: String? = null,
    val style: String? = null,
    val note: String? = null,
)

/** `AnnotationListResponse` (server `packages/types/src/annotation.ts`). Paged: `page` is
 *  1-based, `pageSize` at most 100. */
@Serializable
data class BookOrbitAnnotationsPage(
    val items: List<BookOrbitAnnotationDto> = emptyList(),
    val total: Int? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
)

@Serializable
data class BookOrbitAnnotationDto(
    val id: String? = null,
    val bookId: Long? = null,
    val text: String? = null,
    val note: String? = null,
    val color: String? = null,
    val chapterTitle: String? = null,
    /** issue #266 — a real EPUB CFI (web reader) or null (KOReader/Kobo-synced). */
    val cfi: String? = null,
    /** issue #266 — KOReader's page number, when the highlight came from there. */
    val pageno: Int? = null,
    /** issue #266 — when it was highlighted (ISO-8601), as opposed to when it reached the server. */
    val highlightedAt: String? = null,
    val createdAt: String? = null,
)
