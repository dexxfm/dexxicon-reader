package net.dexxicon.reader.shared.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.progress.BookOrbitAudioProgressUpdate
import net.dexxicon.reader.core.serverapi.progress.GrimmoryFileProgress
import net.dexxicon.reader.core.serverapi.progress.GrimmoryUpdateProgress
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Audiobook position sync — issue #114, following
 * [net.dexxicon.reader.shared.catalog.ReadingStatusActions]'s pattern of a deliberately narrow
 * shared-UI subset of a bigger native-app class, applied here to two of them at once:
 *
 * - **Local storage**: the native app's `core/data/ReadingProgressRepository` wraps
 *   [ReadingProgressDao] (`:core:database`, Room Multiplatform since #58 — already commonMain,
 *   nothing new needed here) with a lot of cross-format bookkeeping (kosync, seeding
 *   "continue" shelves, comic/PDF page locators). This talks to the same DAO directly, for
 *   audiobooks only.
 * - **Remote sync**: the native app's `core/data/sync/NativeProgressSync` (androidMain) also
 *   needs `SyncStateStore`'s "last synced" bookkeeping, which `:shared` has no UI for. This
 *   reimplements only the AUDIOBOOK-shaped slice of its `pull`/`push` directly against
 *   [NativeProgressApi] (already commonMain since #57) — the same way `ReadingStatusActions`
 *   calls it directly for status pushes rather than routing through that Android-only wrapper.
 *
 * Exposes plain, non-`suspend` functions with a completion closure — not `suspend fun` — so
 * Swift can call this directly with the exact same closure-based interop already proven safe
 * by [net.dexxicon.reader.shared.OnOpenReader] (a Kotlin function type Swift implements),
 * rather than depending on Kotlin/Native's `suspend`-function-to-Swift export (untested in
 * this project, and a real per-Kotlin-version risk not worth taking on for this one feature).
 * Both entry points hop onto [Dispatchers.Main] before calling back into Swift — the
 * `AudiobookPlayerViewController` on the other end touches `AVPlayer`/`MPNowPlayingInfoCenter`,
 * which (like any UIKit-adjacent API) expects to be driven from the main thread, and this way
 * it never has to think about which thread a callback fires on.
 *
 * [scope] should be the container's process-lifetime scope (same reasoning as
 * `ReadingStatusActions`) — a position push started right before the user backgrounds the app
 * or closes the reader should still land.
 */
@OptIn(ExperimentalTime::class)
class AudiobookProgressSync(
    private val api: NativeProgressApi,
    private val serverRepository: ServerRepository,
    private val progressDao: ReadingProgressDao,
    private val scope: CoroutineScope,
) {
    /**
     * Resolves the position (ms) playback should resume at — the furthest of the locally
     * stored position and whatever the server has, same "never rewinds" rule as the native
     * app's `ReadingProgressRepository.audiobookResumeMs` — and reports it via [onResolved].
     * Always calls back, even on a network failure (falls back to the local position, or 0).
     */
    fun resolveResumeMs(
        serverId: String,
        bookId: String,
        durationMs: Long,
        onResolved: (Long) -> Unit,
    ) {
        scope.launch {
            val localMs = localPositionMs(serverId, bookId)
            val resolved = runCatching { remoteResumeMs(serverId, bookId, durationMs, localMs) }
                .getOrDefault(localMs)
            withContext(Dispatchers.Main) { onResolved(resolved) }
        }
    }

    /**
     * Saves the current position locally and pushes it to the server. Fire-and-forget from
     * the caller's side (the native player calls this periodically as it plays, same cadence
     * as Android's `ServicePositionWriter`) — a failed remote push is swallowed the same way
     * the native `NativeProgressSync`/`ReadingStatusActions` both already do for a background
     * sync call; the local save always lands regardless, so resume position is never lost
     * even fully offline.
     *
     * [title]/[author]/[coverUrl] are a metadata snapshot (same fields
     * `ReadingProgressRepository.save` merges) so this book can show up on a "Continue
     * listening" shelf without a catalog round-trip — pass whatever the player already has on
     * hand; a null leaves the existing stored value alone. [digestUrl] is the acquisition's
     * own stream URL, already resolved by the caller (same data `ReaderLaunch.kt`'s
     * `OnOpenReader` hands every reader) — needed for BookOrbit's save call, which (unlike its
     * GET) still requires the underlying file's id; there's no dedicated "get the audio file
     * id" endpoint, so the id is parsed from the URL exactly the way the native app's
     * `NativeProgressSync.fileIdFrom` already does.
     */
    fun notePosition(
        serverId: String,
        bookId: String,
        title: String?,
        author: String?,
        coverUrl: String?,
        digestUrl: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        scope.launch {
            saveLocal(serverId, bookId, title, author, coverUrl, digestUrl, positionMs, durationMs)
            runCatching { pushRemote(serverId, bookId, digestUrl, positionMs, durationMs) }
        }
    }

    // ---- local ----

    private suspend fun localPositionMs(serverId: String, bookId: String): Long {
        val locator = progressDao.find(key(serverId, bookId))?.locator ?: return 0L
        return runCatching { Json.decodeFromString<AudiobookLocator>(locator).position }.getOrDefault(0L)
    }

    private suspend fun saveLocal(
        serverId: String,
        bookId: String,
        title: String?,
        author: String?,
        coverUrl: String?,
        digestUrl: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        val existing = progressDao.find(key(serverId, bookId))
        val percent = if (durationMs > 0L) {
            (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        } else {
            existing?.percent
        }
        progressDao.upsert(
            ReadingProgressEntity(
                key = key(serverId, bookId),
                serverId = serverId,
                bookId = bookId,
                percent = percent,
                locator = Json.encodeToString(AudiobookLocator(positionMs)),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                title = title ?: existing?.title,
                author = author ?: existing?.author,
                coverUrl = coverUrl ?: existing?.coverUrl,
                format = ContentFormat.AUDIOBOOK.name,
                digestUrl = digestUrl ?: existing?.digestUrl,
            ),
        )
    }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"

    // ---- remote ----

    private suspend fun remoteResumeMs(
        serverId: String,
        bookId: String,
        durationMs: Long,
        localMs: Long,
    ): Long {
        if (durationMs <= 0L) return localMs
        val server = serverRepository.get(serverId) ?: return localMs
        val remoteMs = pull(server, bookId, durationMs) ?: return localMs
        return maxOf(localMs, remoteMs)
    }

    private suspend fun pull(server: Server, bookId: String, durationMs: Long): Long? = when (server.type) {
        ServerType.BOOKORBIT -> {
            val dto = api.bookOrbitAudioProgress(server.resolve("/api/v1/books/$bookId/audio-progress"))
                .takeIf { it.isSuccessful }?.body()
            dto?.positionSeconds?.let { (it * 1000).toLong() }
                ?: dto?.percentage?.let { (it / 100.0).coerceIn(0.0, 1.0) * durationMs }?.toLong()
        }
        ServerType.GRIMMORY -> {
            val dto = api.grimmoryProgress(server.resolve("/api/v1/app/books/$bookId/progress"))
                .takeIf { it.isSuccessful }?.body()
            val audio = dto?.audiobookProgress
            audio?.positionMs
                ?: audio?.percentage?.let { (it / 100.0).coerceIn(0.0, 1.0) * durationMs }?.toLong()
        }
        else -> null
    }

    private suspend fun pushRemote(
        serverId: String,
        bookId: String,
        digestUrl: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L) return
        val server = serverRepository.get(serverId) ?: return
        val percent = (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        when (server.type) {
            ServerType.BOOKORBIT -> pushBookOrbit(server, bookId, digestUrl, percent, positionMs)
            ServerType.GRIMMORY -> pushGrimmory(server, bookId, percent, positionMs)
            else -> {}
        }
    }

    private suspend fun pushBookOrbit(
        server: Server,
        bookId: String,
        digestUrl: String?,
        percent: Double,
        positionMs: Long,
    ) {
        val fileId = fileIdFrom(digestUrl) ?: return
        api.bookOrbitSaveAudioProgress(
            server.resolve("/api/v1/books/$bookId/audio-progress"),
            BookOrbitAudioProgressUpdate(
                percentage = percent * 100.0,
                currentFileId = fileId,
                positionSeconds = positionMs / 1000.0,
            ),
        )
    }

    /** Same regex `NativeProgressSync.fileIdFrom` uses against a `.../files/{id}/serve` URL —
     *  pure string parsing, no Android dependency, safe to duplicate rather than port the
     *  whole (Android-only) class just for this one helper. */
    private fun fileIdFrom(url: String?): Long? {
        if (url == null) return null
        return Regex("/files/(\\d+)/").find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

    private suspend fun pushGrimmory(server: Server, bookId: String, percent: Double, positionMs: Long) {
        val bookFileId = grimmoryAudiobookFileId(server, bookId) ?: return
        api.grimmorySaveProgress(
            server.resolve("/api/v1/app/books/$bookId/progress"),
            GrimmoryUpdateProgress(
                GrimmoryFileProgress(
                    bookFileId = bookFileId,
                    positionData = positionMs.toString(),
                    progressPercent = percent * 100.0,
                ),
            ),
        )
    }

    /**
     * Not cached (unlike the native `NativeProgressSync.grimmoryBookFileId`'s
     * `ConcurrentHashMap`) — one extra GET per push is an acceptable trade for staying simple;
     * pushes happen every tens of seconds during playback, not on a hot path.
     */
    private suspend fun grimmoryAudiobookFileId(server: Server, bookId: String): Long? {
        val book = api.grimmoryAppBook(server.resolve("/api/v1/app/books/$bookId"))
        return book.files.firstOrNull { it.bookType?.equals("AUDIOBOOK", ignoreCase = true) == true }?.id
            ?: book.files.firstOrNull { it.isPrimaryFile }?.id
            ?: book.files.firstOrNull()?.id
    }
}

/** The audiobook-specific shape of [ReadingProgressEntity.locator] — `{"position":<ms>}`,
 *  the exact same field name the native app's `ReadingProgressRepository.positionMsOf` reads
 *  via `org.json.JSONObject` (JVM-only, unavailable on Kotlin/Native — `:shared` already
 *  depends on kotlinx.serialization for its server DTOs, so this reuses that instead of
 *  hand-rolling JSON string parsing). Each platform's Room database is its own separate file
 *  regardless, so nothing actually reads this cross-platform — matching the field name is
 *  just hygiene, not a real compatibility requirement. */
@Serializable
private data class AudiobookLocator(val position: Long)
