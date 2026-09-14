package net.dexxicon.reader.core.data.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import net.dexxicon.reader.core.common.Logger
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.BookOrbitAudiobookManifest
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.progress.BookOrbitAudioProgressUpdate
import net.dexxicon.reader.core.serverapi.progress.BookOrbitFileProgress
import net.dexxicon.reader.core.serverapi.progress.BookOrbitPlaybackStateUpdate
import net.dexxicon.reader.core.serverapi.progress.GrimmoryFileProgress
import net.dexxicon.reader.core.serverapi.progress.GrimmoryUpdateProgress
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import net.dexxicon.reader.core.serverapi.progress.ServerStatusUpdate

/** A reading position from a server's native (web-reader) progress store. */
data class NativeProgress(
    /** 0.0–1.0 */
    val percent: Double,
    /** Audiobook position, if the store has one. */
    val positionMs: Long? = null,
    /** Comic / PDF 1-based page, if the store has one. */
    val page: Int? = null,
    /** When the server last updated it (epoch millis), if known. */
    val updatedAtMillis: Long? = null,
)

/**
 * Two-way reading-progress sync against the servers' **native** progress APIs — the same
 * ones their web readers use — so a position set on the website shows up in the app and
 * vice-versa. This is separate from (and complementary to) KOReader `kosync`, which is a
 * device-app silo.
 *
 * Phase 4 restructure (issue #126) — moved to commonMain. `@Inject`/`@Singleton` dropped
 * (`javax.inject` isn't available on iOS); `:app`'s `ProgressSyncModule` now provides this
 * explicitly for Hilt, the same pattern `ServerAuthModule` already established for
 * `TokenManager`/`ServerProber`.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class NativeProgressSync(
    private val api: NativeProgressApi,
    private val bookOrbitBrowseApi: BookOrbitBrowseApi,
    private val syncStateStore: SyncStateStore,
    private val io: CoroutineDispatcher,
) {
    /** Grimmory `bookFileId` per "serverId::bookId::format" — avoids re-fetching on every
     * push. A plain map guarded by [Mutex] rather than `ConcurrentHashMap` (JVM-only, not
     * available on Kotlin/Native) — this cache is a pure optimization, so a `Mutex` (cheap,
     * portable) is simpler than reaching for a lock-free structure. */
    private val bookFileIdCacheMutex = Mutex()
    private val bookFileIdCache = mutableMapOf<String, Long>()

    /** Whether a server has the 2.10+ `/audiobooks` controller at all, per `server.id` — a
     * pre-2.10 server always 404s [BookOrbitBrowseApi.audiobookManifestOrNull], so without this
     * every pull/push on an old server would re-probe (and re-404) forever. Same shape as
     * [bookFileIdCache] above. */
    private val audiobookApiSupportCacheMutex = Mutex()
    private val audiobookApiSupportCache = mutableMapOf<String, Boolean>()

    fun supports(server: Server): Boolean =
        server.type == ServerType.BOOKORBIT || server.type == ServerType.GRIMMORY

    /** Set the per-user reading status on the server (BookOrbit / Grimmory only). */
    suspend fun pushStatus(server: Server, bookId: String, status: ReadingStatus) = withContext(io) {
        runCatching {
            val response = when (server.type) {
                ServerType.BOOKORBIT -> api.bookOrbitSetStatus(
                    server.resolve("/api/v1/books/$bookId/status"),
                    ServerStatusUpdate(status.toBookOrbit()),
                )
                ServerType.GRIMMORY -> api.grimmorySetStatus(
                    server.resolve("/api/v1/app/books/$bookId/status"),
                    ServerStatusUpdate(status.toGrimmory()),
                )
                else -> return@runCatching
            }
            if (!response.isSuccessful) {
                error("status ${server.type} $bookId -> HTTP ${response.code()}")
            }
        }.onSuccess {
            syncStateStore.markSynced(server.id)
            Logger.i(TAG, "pushStatus ${server.type} $bookId -> $status ok")
        }.onFailure { Logger.w(TAG, "pushStatus ${server.type} $bookId failed: ${it.message}") }
        Unit
    }

    suspend fun pull(
        server: Server,
        bookId: String,
        format: ContentFormat,
        digestUrl: String?,
    ): NativeProgress? = withContext(io) {
        runCatching {
            when (server.type) {
                ServerType.BOOKORBIT -> pullBookOrbit(server, bookId, format, digestUrl)
                ServerType.GRIMMORY -> pullGrimmory(server, bookId, format)
                else -> null
            }
        }.onSuccess { syncStateStore.markSynced(server.id) }
            .onFailure { Logger.w(TAG, "pull ${server.type} $bookId $format failed: ${it.message}") }
            .getOrNull()
            ?.also { Logger.i(TAG, "pull ${server.type} $bookId $format -> $it") }
    }

    suspend fun push(
        server: Server,
        bookId: String,
        format: ContentFormat,
        digestUrl: String?,
        percent: Double,
        positionMs: Long?,
        position: String?,
    ) = withContext(io) {
        runCatching {
            when (server.type) {
                ServerType.BOOKORBIT -> pushBookOrbit(server, bookId, format, digestUrl, percent, positionMs, position)
                ServerType.GRIMMORY -> pushGrimmory(server, bookId, format, percent, positionMs, position)
                else -> {}
            }
        }.onSuccess {
            syncStateStore.markSynced(server.id)
            Logger.i(TAG, "push ${server.type} $bookId $format pct=$percent pos=${position ?: positionMs} ok")
        }.onFailure { Logger.w(TAG, "push ${server.type} $bookId $format failed: ${it.message}") }
        Unit
    }

    // ---- BookOrbit ----

    private suspend fun pullBookOrbit(
        server: Server,
        bookId: String,
        format: ContentFormat,
        digestUrl: String?,
    ): NativeProgress? {
        if (format == ContentFormat.AUDIOBOOK) {
            return if (bookOrbitAudiobookManifest(server, bookId) != null) {
                val dto = api.bookOrbitPlaybackState(
                    server.resolve("/api/v1/audiobooks/$bookId/playback-state"),
                ).takeIf { it.isSuccessful }?.body() ?: return null
                val pct = dto.percentage ?: return null
                NativeProgress(percent = (pct / 100.0).coerceIn(0.0, 1.0), positionMs = dto.positionMs)
            } else {
                // <=2.9 — see NativeProgressApi's doc comment.
                val dto = api.bookOrbitAudioProgress(
                    server.resolve("/api/v1/books/$bookId/audio-progress"),
                ).takeIf { it.isSuccessful }?.body() ?: return null
                val pct = dto.percentage ?: return null
                NativeProgress(
                    percent = (pct / 100.0).coerceIn(0.0, 1.0),
                    positionMs = dto.positionSeconds?.let { (it * 1000).toLong() },
                )
            }
        }
        val fileId = fileIdFrom(digestUrl) ?: return null
        val dto = api.bookOrbitFileProgress(
            server.resolve("/api/v1/books/files/$fileId/progress"),
        ).takeIf { it.isSuccessful }?.body() ?: return null
        val pct = dto.percentage ?: return null
        return NativeProgress(
            percent = (pct / 100.0).coerceIn(0.0, 1.0),
            page = dto.pageNumber?.takeIf { it > 0 },
        )
    }

    private suspend fun pushBookOrbit(
        server: Server,
        bookId: String,
        format: ContentFormat,
        digestUrl: String?,
        percent: Double,
        positionMs: Long?,
        position: String?,
    ) {
        if (format == ContentFormat.AUDIOBOOK) {
            pushBookOrbitAudiobook(server, bookId, digestUrl, percent, positionMs ?: 0L)
            return
        }
        val fileId = fileIdFrom(digestUrl) ?: return
        val pct100 = (percent.coerceIn(0.0, 1.0) * 100.0)
        val response = api.bookOrbitSaveFileProgress(
            server.resolve("/api/v1/books/files/$fileId/progress"),
            BookOrbitFileProgress(
                cfi = position?.takeIf { format != ContentFormat.PDF && format != ContentFormat.COMIC },
                pageNumber = position?.toIntOrNull(),
                percentage = pct100,
            ),
        )
        if (!response.isSuccessful) {
            val err = response.errorBody()
            error("BookOrbit progress save HTTP ${response.code()}: $err")
        }
    }

    /** issue #192 — the new `playback-state` route is revision-tracked: every write must echo
     * the manifest's current `revision` (`manifestRevision`) and the row's last-seen `revision`
     * (`baseRevision`), and 409s/412s a write that raced another writer or targeted a manifest
     * that has since changed. One retry with freshly re-fetched state covers both — pushes are
     * infrequent (periodic progress saves, not per-frame), so the extra round-trip is cheap.
     * Falls back to the <=2.9 flat audio-progress route on a server without the new controller. */
    private suspend fun pushBookOrbitAudiobook(
        server: Server,
        bookId: String,
        digestUrl: String?,
        percent: Double,
        positionMs: Long,
    ) {
        var lastError = "no attempt made"
        repeat(2) {
            val manifest = bookOrbitAudiobookManifest(server, bookId)
            if (manifest == null) {
                if (it == 0) pushBookOrbitLegacyAudioProgress(server, bookId, digestUrl, percent, positionMs)
                return
            }
            val assetId = manifest.assets.minByOrNull { asset -> asset.sequence }?.assetId ?: return
            val current = api.bookOrbitPlaybackState(
                server.resolve("/api/v1/audiobooks/$bookId/playback-state"),
            ).takeIf { r -> r.isSuccessful }?.body()
            val response = api.bookOrbitSavePlaybackState(
                server.resolve("/api/v1/audiobooks/$bookId/playback-state"),
                BookOrbitPlaybackStateUpdate(
                    assetId = assetId,
                    positionMs = positionMs,
                    capturedAt = Clock.System.now().toString(),
                    operationId = Uuid.random().toString(),
                    baseRevision = current?.revision ?: 0,
                    manifestRevision = manifest.revision,
                ),
            )
            if (response.isSuccessful) return
            lastError = "HTTP ${response.code()}: ${response.errorBody()}"
            if (response.code() != 409 && response.code() != 412) {
                error("BookOrbit playback-state save $lastError")
            }
        }
        error("BookOrbit playback-state save failed after retry: $lastError")
    }

    /** <=2.9 — see NativeProgressApi's doc comment. Unlike the 2.10+ route this needs the
     * underlying file's numeric id, parsed from the stream URL the same way the non-audiobook
     * branch below does — there's no dedicated "get the audio file id" endpoint on old servers. */
    private suspend fun pushBookOrbitLegacyAudioProgress(
        server: Server,
        bookId: String,
        digestUrl: String?,
        percent: Double,
        positionMs: Long,
    ) {
        val fileId = fileIdFrom(digestUrl) ?: return
        val response = api.bookOrbitSaveAudioProgress(
            server.resolve("/api/v1/books/$bookId/audio-progress"),
            BookOrbitAudioProgressUpdate(
                percentage = percent.coerceIn(0.0, 1.0) * 100.0,
                currentFileId = fileId,
                positionSeconds = positionMs / 1000.0,
            ),
        )
        if (!response.isSuccessful) {
            error("BookOrbit progress save HTTP ${response.code()}: ${response.errorBody()}")
        }
    }

    /** Null on a server without the 2.10+ `/audiobooks` controller — cached per [Server.id]
     * (see [audiobookApiSupportCache]) so an old server isn't re-probed on every call. A
     * "supported" answer is never cached as the manifest content itself (only as the boolean),
     * since callers need the manifest's current `revision`/`assets` fresh each time. */
    private suspend fun bookOrbitAudiobookManifest(server: Server, bookId: String): BookOrbitAudiobookManifest? {
        if (audiobookApiSupportCacheMutex.withLock { audiobookApiSupportCache[server.id] } == false) {
            return null
        }
        val manifest = bookOrbitBrowseApi.audiobookManifestOrNull(server.resolve("/api/v1/audiobooks/$bookId/manifest"))
        audiobookApiSupportCacheMutex.withLock { audiobookApiSupportCache[server.id] = manifest != null }
        return manifest
    }

    // ---- Grimmory / BookLore ----

    private suspend fun pullGrimmory(
        server: Server,
        bookId: String,
        format: ContentFormat,
    ): NativeProgress? {
        val response = api.grimmoryProgress(server.resolve("/api/v1/app/books/$bookId/progress"))
        val dto = response.takeIf { it.isSuccessful }?.body() ?: return null

        // Grimmory only fills the format sub-object (cbxProgress/pdfProgress/epubProgress)
        // when a *position* was stored — for a percent-only row it drops back to the
        // top-level `readProgress`. Fall through to it so the % still round-trips.
        val fallbackAt = isoToMillis(dto.lastReadTime)
        fun pct(sub: Double?): Double? = (sub ?: dto.readProgress)?.let { (it / 100.0).coerceIn(0.0, 1.0) }

        return when (format) {
            ContentFormat.AUDIOBOOK -> dto.audiobookProgress?.let {
                val p = it.percentage ?: return null
                NativeProgress((p / 100.0).coerceIn(0.0, 1.0), positionMs = it.positionMs, updatedAtMillis = isoToMillis(it.updatedAt))
            }
            ContentFormat.PDF -> pct(dto.pdfProgress?.percentage)?.let {
                NativeProgress(it, page = dto.pdfProgress?.page, updatedAtMillis = isoToMillis(dto.pdfProgress?.updatedAt) ?: fallbackAt)
            }
            ContentFormat.COMIC -> pct(dto.cbxProgress?.percentage)?.let {
                NativeProgress(it, page = dto.cbxProgress?.page, updatedAtMillis = isoToMillis(dto.cbxProgress?.updatedAt) ?: fallbackAt)
            }
            else -> pct(dto.epubProgress?.percentage)?.let {
                NativeProgress(it, updatedAtMillis = isoToMillis(dto.epubProgress?.updatedAt) ?: fallbackAt)
            }
        }
    }

    private suspend fun pushGrimmory(
        server: Server,
        bookId: String,
        format: ContentFormat,
        percent: Double,
        positionMs: Long?,
        position: String?,
    ) {
        val bookFileId = grimmoryBookFileId(server, bookId, format) ?: return
        val positionData = when (format) {
            ContentFormat.AUDIOBOOK -> positionMs?.toString()
            else -> position
        }
        val body = GrimmoryUpdateProgress(
            GrimmoryFileProgress(
                bookFileId = bookFileId,
                positionData = positionData,
                progressPercent = percent.coerceIn(0.0, 1.0) * 100.0,
            ),
        )
        val response = api.grimmorySaveProgress(server.resolve("/api/v1/app/books/$bookId/progress"), body)
        if (!response.isSuccessful) {
            val err = response.errorBody()
            error("Grimmory progress PUT HTTP ${response.code()}: $err")
        }
    }

    private suspend fun grimmoryBookFileId(server: Server, bookId: String, format: ContentFormat): Long? {
        val cacheKey = "${server.id}::$bookId::$format"
        bookFileIdCacheMutex.withLock { bookFileIdCache[cacheKey] }?.let { return it }

        val book = api.grimmoryAppBook(server.resolve("/api/v1/app/books/$bookId"))
        val wantType = when (format) {
            ContentFormat.AUDIOBOOK -> "AUDIOBOOK"
            ContentFormat.PDF -> "PDF"
            ContentFormat.COMIC -> "CBX"
            else -> "EPUB"
        }
        return (
            book.files.firstOrNull { it.bookType?.equals(wantType, ignoreCase = true) == true }?.id
                ?: book.files.firstOrNull { it.isPrimaryFile }?.id
                ?: book.files.firstOrNull()?.id
            )?.also { id -> bookFileIdCacheMutex.withLock { bookFileIdCache[cacheKey] = id } }
    }

    private fun fileIdFrom(url: String?): Long? {
        if (url == null) return null
        return Regex("/files/(\\d+)/").find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun isoToMillis(iso: String?): Long? =
        iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

    private companion object {
        const val TAG = "NativeProgressSync"
    }
}

/** app status -> Grimmory `ReadStatus` enum name (UPPER). */
private fun ReadingStatus.toGrimmory(): String = when (this) {
    ReadingStatus.UNREAD -> "UNREAD"
    ReadingStatus.WANT_TO_READ -> "UNREAD" // Grimmory has no want-to-read
    ReadingStatus.READING -> "READING"
    ReadingStatus.ON_HOLD -> "PAUSED"
    ReadingStatus.REREADING -> "RE_READING"
    ReadingStatus.READ -> "READ"
    ReadingStatus.ABANDONED -> "ABANDONED"
}

/** app status -> BookOrbit `ReadStatus` (lower_snake). */
private fun ReadingStatus.toBookOrbit(): String = when (this) {
    ReadingStatus.UNREAD -> "unread"
    ReadingStatus.WANT_TO_READ -> "want_to_read"
    ReadingStatus.READING -> "reading"
    ReadingStatus.ON_HOLD -> "on_hold"
    ReadingStatus.REREADING -> "rereading"
    ReadingStatus.READ -> "read"
    ReadingStatus.ABANDONED -> "abandoned"
}
