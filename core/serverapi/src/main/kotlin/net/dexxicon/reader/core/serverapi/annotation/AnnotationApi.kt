package net.dexxicon.reader.core.serverapi.annotation

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * BookLore / Grimmory annotation CRUD (JWT bearer):
 *   GET    /api/v1/annotations/book/{bookId}
 *   POST   /api/v1/annotations
 *   PUT    /api/v1/annotations/{id}
 *   DELETE /api/v1/annotations/{id}
 * BookOrbit exposes only a read view: `GET /api/v1/annotations?bookId={id}` → { items: [...] }.
 */
interface AnnotationApi {

    @GET
    suspend fun listForBook(@Url url: String): List<AnnotationDto>

    @GET
    suspend fun listBookOrbit(@Url url: String): BookOrbitAnnotationsPage

    @POST
    suspend fun create(@Url url: String, @Body body: CreateAnnotationDto): AnnotationDto

    @PUT
    suspend fun update(@Url url: String, @Body body: UpdateAnnotationDto): AnnotationDto

    @DELETE
    suspend fun delete(@Url url: String): Response<Unit>
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

@Serializable
data class BookOrbitAnnotationsPage(
    val items: List<BookOrbitAnnotationDto> = emptyList(),
)

@Serializable
data class BookOrbitAnnotationDto(
    val id: String? = null,
    val bookId: Long? = null,
    val text: String? = null,
    val note: String? = null,
    val color: String? = null,
    val chapterTitle: String? = null,
)
