package net.dexxicon.reader.core.serverapi.bookmark

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

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
 * `cfi`.
 */
interface BookmarkApi {

    @GET
    suspend fun list(@Url url: String): List<BookmarkDto>

    @POST
    suspend fun createBookOrbit(@Url url: String, @Body body: BookOrbitBookmarkBody): BookmarkDto

    @POST
    suspend fun createGrimmory(@Url url: String, @Body body: GrimmoryBookmarkBody): BookmarkDto

    @DELETE
    suspend fun delete(@Url url: String): Response<Unit>
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
