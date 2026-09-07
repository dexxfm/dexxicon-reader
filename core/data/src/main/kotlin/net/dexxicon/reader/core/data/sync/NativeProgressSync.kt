package net.dexxicon.reader.core.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.progress.BookOrbitAudioProgressUpdate
import net.dexxicon.reader.core.serverapi.progress.BookOrbitFileProgress
import net.dexxicon.reader.core.serverapi.progress.GrimmoryFileProgress
import net.dexxicon.reader.core.serverapi.progress.GrimmoryUpdateProgress
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A reading position from a server's native (web-reader) progress store. */
data class NativeProgress(
    /** 0.0–1.0 */
    val percent: Double,
    /** Audiobook position, if the store has one. */
    val positionMs: Long? = null,
    /** When the server last updated it (epoch millis), if known. */
    val updatedAtMillis: Long? = null,
)

/**
 * Two-way reading-progress sync against the servers' **native** progress APIs — the same
 * ones their web readers use — so a position set on the website shows up in the app and
 * vice-versa. This is separate from (and complementary to) KOReader `kosync`, which is a
 * device-app silo.
 */
@Singleton
class NativeProgressSync @Inject constructor(
    private val api: NativeProgressApi,
    private val syncStateStore: SyncStateStore,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    /** Grimmory `bookFileId` per "serverId::bookId::format" — avoids re-fetching on every push. */
    private val bookFileIdCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun supports(server: Server): Boolean =
        server.type == ServerType.BOOKORBIT || server.type == ServerType.GRIMMORY

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
            .onFailure { Log.w(TAG, "pull ${server.type} $bookId $format failed: ${it.message}") }
            .getOrNull()
            ?.also { Log.i(TAG, "pull ${server.type} $bookId $format -> $it") }
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
            Log.i(TAG, "push ${server.type} $bookId $format pct=$percent posMs=$positionMs ok")
        }.onFailure { Log.w(TAG, "push ${server.type} $bookId $format failed: ${it.message}") }
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
            val dto = api.bookOrbitAudioProgress(
                server.resolve("/api/v1/books/$bookId/audio-progress"),
            ).takeIf { it.isSuccessful }?.body() ?: return null
            val pct = dto.percentage ?: return null
            return NativeProgress(
                percent = (pct / 100.0).coerceIn(0.0, 1.0),
                positionMs = dto.positionSeconds?.let { (it * 1000).toLong() },
            )
        }
        val fileId = fileIdFrom(digestUrl) ?: return null
        val dto = api.bookOrbitFileProgress(
            server.resolve("/api/v1/books/files/$fileId/progress"),
        ).takeIf { it.isSuccessful }?.body() ?: return null
        val pct = dto.percentage ?: return null
        return NativeProgress(percent = (pct / 100.0).coerceIn(0.0, 1.0))
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
        val fileId = fileIdFrom(digestUrl) ?: return
        val pct100 = (percent.coerceIn(0.0, 1.0) * 100.0)
        if (format == ContentFormat.AUDIOBOOK) {
            api.bookOrbitSaveAudioProgress(
                server.resolve("/api/v1/books/$bookId/audio-progress"),
                BookOrbitAudioProgressUpdate(
                    percentage = pct100,
                    currentFileId = fileId,
                    positionSeconds = (positionMs ?: 0L) / 1000.0,
                ),
            )
        } else {
            api.bookOrbitSaveFileProgress(
                server.resolve("/api/v1/books/files/$fileId/progress"),
                BookOrbitFileProgress(
                    cfi = position?.takeIf { format != ContentFormat.PDF && format != ContentFormat.COMIC },
                    pageNumber = position?.toIntOrNull(),
                    percentage = pct100,
                ),
            )
        }
    }

    // ---- Grimmory / BookLore ----

    private suspend fun pullGrimmory(
        server: Server,
        bookId: String,
        format: ContentFormat,
    ): NativeProgress? {
        val dto = api.grimmoryProgress(server.resolve("/api/v1/app/books/$bookId/progress"))
            .takeIf { it.isSuccessful }?.body() ?: return null
        return when (format) {
            ContentFormat.AUDIOBOOK -> dto.audiobookProgress?.let {
                val pct = it.percentage ?: return null
                NativeProgress((pct / 100.0).coerceIn(0.0, 1.0), it.positionMs, isoToMillis(it.updatedAt))
            }
            ContentFormat.PDF -> dto.pdfProgress?.let {
                NativeProgress(((it.percentage ?: return null) / 100.0).coerceIn(0.0, 1.0), null, isoToMillis(it.updatedAt))
            }
            ContentFormat.COMIC -> dto.cbxProgress?.let {
                NativeProgress(((it.percentage ?: return null) / 100.0).coerceIn(0.0, 1.0), null, isoToMillis(it.updatedAt))
            }
            else -> dto.epubProgress?.let {
                NativeProgress(((it.percentage ?: return null) / 100.0).coerceIn(0.0, 1.0), null, isoToMillis(it.updatedAt))
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
        api.grimmorySaveProgress(
            server.resolve("/api/v1/app/books/$bookId/progress"),
            GrimmoryUpdateProgress(
                GrimmoryFileProgress(
                    bookFileId = bookFileId,
                    positionData = positionData,
                    progressPercent = percent.coerceIn(0.0, 1.0) * 100.0,
                ),
            ),
        )
    }

    private suspend fun grimmoryBookFileId(server: Server, bookId: String, format: ContentFormat): Long? {
        val cacheKey = "${server.id}::$bookId::$format"
        bookFileIdCache[cacheKey]?.let { return it }

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
            )?.also { bookFileIdCache[cacheKey] = it }
    }

    private fun fileIdFrom(url: String?): Long? {
        if (url == null) return null
        return Regex("/files/(\\d+)/").find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun isoToMillis(iso: String?): Long? =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private companion object {
        const val TAG = "NativeProgressSync"
    }
}
