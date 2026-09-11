package net.dexxicon.reader.core.serverapi.progress

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.network.ApiResponse
import net.dexxicon.reader.core.network.apiResponse

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
class NativeProgressApi(private val client: HttpClient) {

    // GETs return ApiResponse<T>: the servers answer 200 with a literal `null` body when a
    // book has no progress yet — ApiResponse's own deserialize-failure tolerance turns that
    // into a null body(), same job NullableBodyConverterFactory did for Retrofit.

    suspend fun bookOrbitFileProgress(url: String): ApiResponse<BookOrbitFileProgress> =
        client.apiResponse(url) { method = HttpMethod.Get }

    suspend fun bookOrbitSaveFileProgress(url: String, body: BookOrbitFileProgress): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun bookOrbitAudioProgress(url: String): ApiResponse<BookOrbitAudioProgress> =
        client.apiResponse(url) { method = HttpMethod.Get }

    suspend fun bookOrbitSaveAudioProgress(url: String, body: BookOrbitAudioProgressUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Patch
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun grimmoryProgress(url: String): ApiResponse<GrimmoryProgressResponse> =
        client.apiResponse(url) { method = HttpMethod.Get }

    suspend fun grimmorySaveProgress(url: String, body: GrimmoryUpdateProgress): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Put
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun grimmoryAppBook(url: String): GrimmoryAppBook = client.get(url).body()

    // ---- reading status ----
    //  BookOrbit  PATCH  /api/v1/books/{id}/status        {status:"reading"}  (lower_snake)
    //  Grimmory   PUT    /api/v1/app/books/{id}/status    {status:"READING"}  (UPPER)

    suspend fun bookOrbitSetStatus(url: String, body: ServerStatusUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Patch
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun grimmorySetStatus(url: String, body: ServerStatusUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Put
            contentType(ContentType.Application.Json)
            setBody(body)
        }
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
