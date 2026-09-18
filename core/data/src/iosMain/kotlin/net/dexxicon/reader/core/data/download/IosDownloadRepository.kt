package net.dexxicon.reader.core.data.download

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.database.entity.DownloadEntity
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.fileExtension
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Real, Ktor-backed [DownloadRepository] (issue #174) — no `WorkManager` equivalent exists on
 * iOS, so this streams each download in a plain coroutine on [appScope] instead, tracked in
 * [jobs] so [remove]/[removeAllForServer] can cancel an in-flight one the same way Android's
 * `workManager.cancelUniqueWork` does. [dao] is the exact same commonMain `DownloadDao` Android
 * uses (same `downloads` table), so a book downloaded here shows up identically in Library/Home.
 *
 * Deliberately out of scope, matching what was actually asked for and verified this pass:
 * downloads only run while the app is in the foreground (no `URLSession` background-transfer
 * task — that needs app-delegate plumbing `iosApp` doesn't have yet) and [AppPreferences
 * .downloadsWifiOnly][net.dexxicon.reader.core.datastore.AppPreferences.downloadsWifiOnly]
 * isn't enforced (Darwin's `HttpClientEngine` has no per-request network-type constraint the
 * way `WorkManager`'s `Constraints` does). Both are real gaps for later work, not silently
 * pretended-away: the storage-limit check below still applies, matching Android.
 */
class IosDownloadRepository(
    private val httpClient: HttpClient,
    private val dao: DownloadDao,
    private val appPreferences: AppPreferencesStore,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : DownloadRepository {
    override val supportsDownloads: Boolean = true

    private val fs = FileSystem.SYSTEM
    private val jobsMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    override val downloads: Flow<List<Download>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override val usedBytes: Flow<Long> =
        dao.observeAll().map { list -> list.sumOf { it.approxBytes() } }

    private fun DownloadEntity.approxBytes(): Long =
        totalBytes ?: downloadedBytes.takeIf { it > 0L } ?: 0L

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    override fun download(serverId: String, bookId: String): Flow<Download?> =
        dao.observe(key(serverId, bookId)).map { it?.toDomain() }

    override suspend fun get(serverId: String, bookId: String): Download? = withContext(io) {
        dao.find(key(serverId, bookId))?.toDomain()
    }

    override suspend fun enqueue(detail: BookDetail): EnqueueResult = withContext(io) {
        val prefs = appPreferences.preferences.first()
        val s = detail.summary
        val acquisition = detail.acquisitions.firstOrNull { it.format == s.format }
            ?: detail.primaryAcquisition
            ?: return@withContext EnqueueResult.NoFile

        val limit = prefs.downloadLimitBytes
        val needed = detail.fileSizeBytes ?: 0L
        if (limit != null && needed > 0L) {
            fun sizeOf(d: Download) = d.totalBytes ?: d.downloadedBytes.coerceAtLeast(0L)
            val all = downloads.first()
            // Don't count an existing copy of this same book against the limit (re-download).
            val alreadyHeld = all.firstOrNull { it.serverId == s.serverId && it.bookId == s.id }
                ?.let(::sizeOf) ?: 0L
            val projected = all.sumOf(::sizeOf) - alreadyHeld + needed
            if (projected > limit) {
                _messages.tryEmit(
                    "Not downloaded — it would exceed your ${formatGb(limit)} storage limit. " +
                        "Free up space or raise the limit in Settings.",
                )
                return@withContext EnqueueResult.OverLimit(
                    neededBytes = needed,
                    usedBytes = (all.sumOf(::sizeOf) - alreadyHeld).coerceAtLeast(0L),
                    limitBytes = limit,
                )
            }
        }

        val entity = DownloadEntity.new(
            serverId = s.serverId,
            bookId = s.id,
            title = s.title,
            authors = s.authors,
            series = s.series,
            coverUrl = s.coverUrl,
            format = s.format,
            sourceUrl = acquisition.href,
            durationMs = detail.audio?.durationMs,
        )
        dao.upsert(entity)
        startDownload(entity.key)
        EnqueueResult.Queued
    }

    private suspend fun startDownload(key: String) {
        val job = appScope.launch(io) { runDownload(key) }
        jobsMutex.withLock { jobs.put(key, job)?.cancel() }
    }

    private suspend fun runDownload(key: String) {
        val entity = dao.find(key) ?: return
        val format = runCatching { ContentFormat.valueOf(entity.format) }
            .getOrDefault(ContentFormat.UNKNOWN)
        val dir = booksDir(entity.serverId)
        fs.createDirectories(dir)
        val target = dir / "${entity.bookId}.${format.fileExtension}"
        val partial = dir / "${target.name}.part"

        try {
            mark(key, DownloadStatus.RUNNING, 0L, null, null, null)
            httpClient.prepareGet(entity.sourceUrl).execute { response ->
                val total = response.contentLength()?.takeIf { it > 0 }
                val channel = response.bodyAsChannel()
                fs.sink(partial).buffer().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        sink.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= REPORT_EVERY_BYTES) {
                            mark(key, DownloadStatus.RUNNING, downloaded, total, null, null)
                            lastReported = downloaded
                        }
                    }
                }
            }
            fs.atomicMove(partial, target)
            val size = fs.metadata(target).size ?: 0L
            mark(key, DownloadStatus.DONE, size, size, target.toString(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            fail(key, partial, "Server returned HTTP ${e.response.status.value}")
        } catch (e: Exception) {
            fail(key, partial, e.message ?: "Download failed")
        }
    }

    private suspend fun fail(key: String, partial: Path, message: String) {
        runCatching { fs.delete(partial) }
        mark(key, DownloadStatus.FAILED, 0L, null, null, message)
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun mark(
        key: String,
        status: DownloadStatus,
        downloaded: Long,
        total: Long?,
        localPath: String?,
        error: String?,
    ) = dao.updateState(
        key = key,
        status = status.name,
        downloaded = downloaded,
        total = total,
        localPath = localPath,
        error = error,
        updatedAt = Clock.System.now().toEpochMilliseconds(),
    )

    override suspend fun remove(serverId: String, bookId: String) = withContext(io) {
        val key = key(serverId, bookId)
        cancelJob(key)
        dao.find(key)?.localPath?.let { deleteLocal(it) }
        dao.deleteByKey(key)
    }

    override suspend fun removeAllForServer(serverId: String) = withContext(io) {
        dao.forServer(serverId).forEach { entity ->
            cancelJob(entity.key)
            entity.localPath?.let { deleteLocal(it) }
        }
        dao.deleteForServer(serverId)
    }

    override suspend fun removeOrphaned() = withContext(io) {
        dao.findOrphaned().forEach { entity ->
            cancelJob(entity.key)
            entity.localPath?.let { deleteLocal(it) }
        }
        dao.deleteOrphaned()
    }

    private fun deleteLocal(path: String) {
        runCatching { fs.delete(path.toPath()) }
        runCatching { fs.delete("$path.part".toPath()) }
    }

    private suspend fun cancelJob(key: String) {
        jobsMutex.withLock { jobs.remove(key)?.cancel() }
    }

    override suspend fun localFile(serverId: String, bookId: String): String? = withContext(io) {
        val entity = dao.find(key(serverId, bookId)) ?: return@withContext null
        if (entity.status != DownloadStatus.DONE.name) return@withContext null
        entity.localPath?.takeIf { fs.exists(it.toPath()) }
    }

    /** `Documents/library/{serverId}/` — same relative shape as Android's `filesDir/library/
     * {serverId}/`, just rooted at iOS's own sandboxed Documents directory (same directory
     * :core:datastore's iOS actuals already resolve their own files under). */
    private fun booksDir(serverId: String): Path {
        val documentsDir = NSFileManager.defaultManager.URLsForDirectory(
            NSDocumentDirectory,
            NSUserDomainMask,
        ).firstOrNull() as? NSURL
        val base = documentsDir?.path ?: NSFileManager.defaultManager.currentDirectoryPath
        return "$base/library/$serverId".toPath()
    }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 10 || gb == gb.toLong().toDouble()) return "${gb.toLong()} GB"
        val tenths = (gb * 10).toLong()
        return "${tenths / 10}.${tenths % 10} GB"
    }

    private companion object {
        const val REPORT_EVERY_BYTES = 1024L * 1024
    }
}
