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
 *  BookOrbit  audiobook (<=2.9):      GET      `/api/v1/books/{bookId}/audio-progress`
 *                                     PATCH    `/api/v1/books/{bookId}/audio-progress`
 *  BookOrbit  audiobook (2.10+):      GET      `/api/v1/audiobooks/{bookId}/playback-state`
 *                                     PUT      `/api/v1/audiobooks/{bookId}/playback-state`
 *  Grimmory   all types:             GET/PUT  `/api/v1/app/books/{bookId}/progress`
 *  Grimmory   book-file lookup:       GET      `/api/v1/app/books/{bookId}`  (for bookFileId)
 *
 * Percentages are 0–100 on the wire.
 *
 * issue #192 — server 2.10.0 replaced the flat `GET/PATCH
 * /api/v1/books/{bookId}/audio-progress` with a revision-tracked `playback-state` route
 * (optimistic concurrency: every write echoes back the manifest's current `revision`, as
 * `manifestRevision` — see [net.dexxicon.reader.core.serverapi.browse.BookOrbitAudiobookManifest]
 * — and the last-seen `revision` of the playback-state row itself, as `baseRevision`; the
 * server 409s/412s a write that raced or targeted a stale manifest). The old route is still
 * used against servers older than 2.10 (detected by the new manifest endpoint 404ing — see
 * [net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi.audiobookManifestOrNull]), so
 * both sets of methods/DTOs below stay live rather than one replacing the other.
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

    /** <=2.9 only — see [NativeProgressApi]'s doc comment. */
    suspend fun bookOrbitAudioProgress(url: String): ApiResponse<BookOrbitAudioProgress> =
        client.apiResponse(url) { method = HttpMethod.Get }

    /** <=2.9 only — see [NativeProgressApi]'s doc comment. */
    suspend fun bookOrbitSaveAudioProgress(url: String, body: BookOrbitAudioProgressUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Patch
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** 2.10+ only — see [NativeProgressApi]'s doc comment. */
    suspend fun bookOrbitPlaybackState(url: String): ApiResponse<BookOrbitPlaybackState> =
        client.apiResponse(url) { method = HttpMethod.Get }

    /** 2.10+ only — see [NativeProgressApi]'s doc comment. */
    suspend fun bookOrbitSavePlaybackState(url: String, body: BookOrbitPlaybackStateUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Put
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

/** <=2.9 servers only (issue #192) — the route this backs was removed in 2.10. */
@Serializable
data class BookOrbitAudioProgress(
    val currentFileId: Long? = null,
    val positionSeconds: Double? = null,
    val percentage: Double? = null,
)

/** <=2.9 servers only (issue #192) — the route this backs was removed in 2.10. */
@Serializable
data class BookOrbitAudioProgressUpdate(
    val percentage: Double,
    val currentFileId: Long,
    val positionSeconds: Double,
)

/** `GET /api/v1/audiobooks/{bookId}/playback-state` response (2.10+, issue #192) —
 * `revision`/`manifestRevision` must be echoed back on the next write (see
 * [NativeProgressApi]'s doc comment). */
@Serializable
data class BookOrbitPlaybackState(
    val assetId: String? = null,
    val positionMs: Long? = null,
    val percentage: Double? = null,
    val revision: Int? = null,
    val manifestRevision: String? = null,
)

@Serializable
data class BookOrbitPlaybackStateUpdate(
    val assetId: String,
    val positionMs: Long,
    val capturedAt: String,
    val operationId: String,
    val baseRevision: Int,
    val manifestRevision: String,
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
