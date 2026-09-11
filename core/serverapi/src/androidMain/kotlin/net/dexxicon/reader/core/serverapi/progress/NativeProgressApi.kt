package net.dexxicon.reader.core.serverapi.progress

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * Native reading-progress APIs — the ones the servers' own web readers use, so progress
 * round-trips with the website (unlike the KOReader `kosync` silo).
 *
 *  BookOrbit  file (epub/pdf/comic):  GET/POST `/api/v1/books/files/{fileId}/progress`
 *  BookOrbit  audiobook:              GET      `/api/v1/books/{bookId}/audio-progress`
 *                                     PATCH    `/api/v1/books/{bookId}/audio-progress`
 *  Grimmory   all types:             GET/PUT  `/api/v1/app/books/{bookId}/progress`
 *  Grimmory   book-file lookup:       GET      `/api/v1/app/books/{bookId}`  (for bookFileId)
 *
 * Percentages are 0–100 on the wire.
 */
interface NativeProgressApi {

    // GETs return Response<T>: the servers answer 200 with a literal `null` body when a book
    // has no progress yet — NullableBodyConverterFactory turns that into a null body().

    @GET
    suspend fun bookOrbitFileProgress(@Url url: String): Response<BookOrbitFileProgress>

    @POST
    suspend fun bookOrbitSaveFileProgress(
        @Url url: String,
        @Body body: BookOrbitFileProgress,
    ): Response<Unit>

    @GET
    suspend fun bookOrbitAudioProgress(@Url url: String): Response<BookOrbitAudioProgress>

    @HTTP(method = "PATCH", hasBody = true)
    suspend fun bookOrbitSaveAudioProgress(
        @Url url: String,
        @Body body: BookOrbitAudioProgressUpdate,
    ): Response<Unit>

    @GET
    suspend fun grimmoryProgress(@Url url: String): Response<GrimmoryProgressResponse>

    @PUT
    suspend fun grimmorySaveProgress(
        @Url url: String,
        @Body body: GrimmoryUpdateProgress,
    ): Response<Unit>

    @GET
    suspend fun grimmoryAppBook(@Url url: String): GrimmoryAppBook

    // ---- reading status ----
    //  BookOrbit  PATCH  /api/v1/books/{id}/status        {status:"reading"}  (lower_snake)
    //  Grimmory   PUT    /api/v1/app/books/{id}/status    {status:"READING"}  (UPPER)

    @HTTP(method = "PATCH", hasBody = true)
    suspend fun bookOrbitSetStatus(@Url url: String, @Body body: ServerStatusUpdate): Response<Unit>

    @PUT
    suspend fun grimmorySetStatus(@Url url: String, @Body body: ServerStatusUpdate): Response<Unit>
}

@Serializable
data class ServerStatusUpdate(val status: String)

// ---- BookOrbit ----

@Serializable
data class BookOrbitFileProgress(
    val cfi: String? = null,
    val pageNumber: Int? = null,
    val percentage: Double? = null,
    val koreaderProgress: String? = null,
)

@Serializable
data class BookOrbitAudioProgress(
    val currentFileId: Long? = null,
    val positionSeconds: Double? = null,
    val percentage: Double? = null,
)

@Serializable
data class BookOrbitAudioProgressUpdate(
    val percentage: Double,
    val currentFileId: Long,
    val positionSeconds: Double,
)

// ---- Grimmory / BookLore ----

@Serializable
data class GrimmoryProgressResponse(
    val readProgress: Double? = null,
    val readStatus: String? = null,
    val lastReadTime: String? = null,
    val epubProgress: GrimmoryEpubProgress? = null,
    val pdfProgress: GrimmoryPagedProgress? = null,
    val cbxProgress: GrimmoryPagedProgress? = null,
    val audiobookProgress: GrimmoryAudiobookProgress? = null,
)

@Serializable
data class GrimmoryEpubProgress(
    val cfi: String? = null,
    val href: String? = null,
    val percentage: Double? = null,
    val updatedAt: String? = null,
)

@Serializable
data class GrimmoryPagedProgress(
    val page: Int? = null,
    val percentage: Double? = null,
    val updatedAt: String? = null,
)

@Serializable
data class GrimmoryAudiobookProgress(
    val positionMs: Long? = null,
    val trackIndex: Int? = null,
    val percentage: Double? = null,
    val updatedAt: String? = null,
)

/** `UpdateProgressRequest` — we always use the non-deprecated `fileProgress` field. */
@Serializable
data class GrimmoryUpdateProgress(
    val fileProgress: GrimmoryFileProgress,
)

@Serializable
data class GrimmoryFileProgress(
    val bookFileId: Long,
    val positionData: String? = null,
    val positionHref: String? = null,
    val progressPercent: Double,
    val ttsPositionCfi: String? = null,
    val contentSourceProgressPercent: Double? = null,
)

@Serializable
data class GrimmoryAppBook(
    val id: Long? = null,
    val files: List<GrimmoryAppBookFile> = emptyList(),
)

@Serializable
data class GrimmoryAppBookFile(
    val id: Long,
    val bookType: String? = null,
    // Jackson may emit either name depending on the getter; accept both.
    val isPrimary: Boolean = false,
    val primary: Boolean = false,
) {
    val isPrimaryFile: Boolean get() = isPrimary || primary
}
