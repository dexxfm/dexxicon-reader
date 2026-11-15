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
 *  Grimmory   read, all types:       GET      `/api/v1/books/{bookId}`  (the book's own
 *                                     epub/pdf/cbx/audiobookProgress, plus its file ids)
 *  Grimmory   write, all types:      POST     `/api/v1/books/progress`  {bookId, fileProgress}
 *  (issue #335 — Grimmory's supported web API; its `/api/v1/app/…` API is being removed.)
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

    /** `GET /api/v1/books/{bookId}` — the book carries the current user's progress. */
    suspend fun grimmoryProgress(url: String): ApiResponse<GrimmoryProgressResponse> =
        client.apiResponse(url) { method = HttpMethod.Get }

    /** `POST /api/v1/books/progress` (204). */
    suspend fun grimmorySaveProgress(url: String, body: GrimmoryUpdateProgress): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** `GET /api/v1/books/{bookId}` — for the id of the file a progress write is keyed by. */
    suspend fun grimmoryBookFiles(url: String): GrimmoryBookFiles = client.get(url).body()

    // ---- reading status ----
    //  BookOrbit  PATCH  /api/v1/books/{id}/status        {status:"reading"}  (lower_snake)
    //  Grimmory   POST   /api/v1/books/status             {bookIds:[id],status:"READING"}

    suspend fun bookOrbitSetStatus(url: String, body: ServerStatusUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Patch
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun grimmorySetStatus(url: String, body: GrimmoryStatusUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // ---- personal rating (issue #264) ----
    //  BookOrbit  PATCH  /api/v1/books/{id}/metadata      {rating:1-5}     (part of the general
    //                                                                      metadata-update body)
    //  Grimmory   PUT    /api/v1/books/personal-rating    {ids:[id],rating:1-5}

    suspend fun bookOrbitSetRating(url: String, body: BookOrbitRatingUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Patch
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun grimmorySetRating(url: String, body: GrimmoryRatingUpdate): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Put
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}

@Serializable
data class ServerStatusUpdate(val status: String)

/** Grimmory's `ReadStatusUpdateRequest` is bulk-shaped even for a single book. */
@Serializable
data class GrimmoryStatusUpdate(val bookIds: List<Long>, val status: String)

/** Sent as a partial body to BookOrbit's general `PATCH .../metadata` route (every field of
 *  the server's own `UpdateBookMetadataDto` is optional) — omitting every field but `rating`
 *  leaves the rest of the book's metadata untouched. */
@Serializable
data class BookOrbitRatingUpdate(val rating: Int)

/** Grimmory's rating route is bulk-shaped even for a single book. */
@Serializable
data class GrimmoryRatingUpdate(val ids: List<Long>, val rating: Int)

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

/** The progress fields of Grimmory's `Book` (issue #335: read from `GET /api/v1/books/{id}`). */
@Serializable
data class GrimmoryProgressResponse(
    /** Only the old `/app` progress endpoint had this top-level %; kept for that shape. */
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

/** `ReadProgressRequest` — we always use the non-deprecated `fileProgress` field. */
@Serializable
data class GrimmoryUpdateProgress(
    val bookId: Long,
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

/** The file ids of Grimmory's `Book`: its primary file, then its other formats. */
@Serializable
data class GrimmoryBookFiles(
    val id: Long? = null,
    val primaryFile: GrimmoryBookFileRef? = null,
    val alternativeFormats: List<GrimmoryBookFileRef> = emptyList(),
) {
    val files: List<GrimmoryBookFileRef> get() = listOfNotNull(primaryFile) + alternativeFormats

    /** The file of [bookType] (EPUB/PDF/CBX/AUDIOBOOK), else the primary file. */
    fun fileIdFor(bookType: String): Long? =
        (files.firstOrNull { it.bookType.equals(bookType, ignoreCase = true) } ?: files.firstOrNull())?.id
}

@Serializable
data class GrimmoryBookFileRef(
    val id: Long,
    val bookType: String? = null,
)
