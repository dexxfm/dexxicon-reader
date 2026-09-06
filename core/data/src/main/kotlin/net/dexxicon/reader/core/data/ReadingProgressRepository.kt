package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.sync.DigestSource
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores where each book was last left off. Position tokens are opaque (see [ReadingProgress]).
 *
 * Progress is **two-way** with the server's KOReader sync endpoint: every [save] with a
 * percentage pushes it (best-effort, off the caller's thread); [refreshFromKoSync] pulls
 * newer positions set elsewhere (the KOReader app, another device) back into the local rows
 * so the Library "Continue" shelves reflect them.
 */
@Singleton
class ReadingProgressRepository @Inject constructor(
    private val dao: ReadingProgressDao,
    private val koSync: KoSyncRepository,
    private val serverRepository: ServerRepository,
    private val downloadRepository: DownloadRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    fun observe(serverId: String, bookId: String): Flow<ReadingProgress?> =
        dao.observe(key(serverId, bookId)).map { it?.toDomain() }

    fun observeForServer(serverId: String): Flow<Map<String, ReadingProgress>> =
        dao.observeForServer(serverId).map { list ->
            list.associate { it.bookId to it.toDomain() }
        }

    /** Started-but-unfinished books, most recently touched first. */
    fun observeInProgress(): Flow<List<ReadingProgress>> =
        dao.observeInProgress().map { list -> list.map { it.toDomain() } }

    suspend fun get(serverId: String, bookId: String): ReadingProgress? = withContext(io) {
        dao.find(key(serverId, bookId))?.toDomain()
    }

    /**
     * Persist a position update. Metadata fields ([ReadingProgress.title] etc.) are merged —
     * a null on [progress] keeps whatever the row already had — so a bare position save from
     * a reader doesn't wipe the snapshot captured when the book was opened. When [progress]
     * carries a percentage it is also pushed to KOReader sync.
     */
    suspend fun save(progress: ReadingProgress) = withContext(io) {
        val existing = dao.find(progress.key)
        val merged = ReadingProgressEntity.fromDomain(
            progress.copy(
                updatedAt = System.currentTimeMillis(),
                title = progress.title ?: existing?.title,
                author = progress.author ?: existing?.author,
                coverUrl = progress.coverUrl ?: existing?.coverUrl,
                format = progress.format
                    ?: existing?.format?.let {
                        runCatching { ContentFormat.valueOf(it) }.getOrNull()
                    },
                digestUrl = progress.digestUrl ?: existing?.digestUrl,
                percent = progress.percent ?: existing?.percent,
                locator = progress.locator ?: existing?.locator,
            ),
        )
        dao.upsert(merged)

        if (progress.percent != null) {
            val domain = merged.toDomain()
            appScope.launch { pushToKoSync(domain) }
        }
    }

    suspend fun clear(serverId: String, bookId: String) = withContext(io) {
        dao.deleteByKey(key(serverId, bookId))
    }

    /**
     * The position (ms) an audiobook should resume at: the furthest of the local position and
     * anything KOReader sync has for this book. Never rewinds.
     */
    suspend fun audiobookResumeMs(
        serverId: String,
        bookId: String,
        digestUrl: String?,
        durationMs: Long,
        localMs: Long,
    ): Long = withContext(io) {
        if (durationMs <= 0L) return@withContext localMs
        val server = serverRepository.get(serverId) ?: return@withContext localMs
        if (!koSync.isConfigured(server)) return@withContext localMs
        val source = digestSourceFor(serverId, bookId, digestUrl) ?: return@withContext localMs
        val remote = runCatching { koSync.pull(server, key(serverId, bookId), source) }.getOrNull()
            ?: return@withContext localMs
        val remoteMs = (remote.percentage.coerceIn(0.0, 1.0) * durationMs).toLong()
        maxOf(localMs, remoteMs)
    }

    /**
     * Reconcile every in-progress book with KOReader sync **both ways**: adopt the server's
     * position when it is newer than ours, push ours when it is newer than the server's (or
     * the server has nothing yet). Cheap for downloaded books (local digest); for stream-only
     * books it needs the [ReadingProgress.digestUrl] captured at open time.
     */
    suspend fun syncWithKoSync() = withContext(io) {
        // Touch every configured account first so each one records a "last synced" time,
        // even servers with nothing in progress right now.
        serverRepository.servers.first()
            .filter { koSync.isConfigured(it) }
            .forEach { server -> runCatching { koSync.verify(server) } }

        val rows = dao.all()
        val servers = rows.map { it.serverId }.distinct()
            .mapNotNull { id -> serverRepository.get(id)?.let { id to it } }
            .toMap()

        for (row in rows) {
            val server = servers[row.serverId] ?: continue
            if (!koSync.isConfigured(server)) continue
            val localPercent = row.percent ?: continue
            val source = digestSourceFor(row.serverId, row.bookId, row.digestUrl) ?: continue

            val remote = runCatching { koSync.pull(server, row.key, source) }.getOrNull()
            if (remote == null) {
                // Server has nothing (or was unreachable) — offer our position.
                runCatching {
                    koSync.push(server, row.key, source, localPercent.coerceIn(0.0, 1.0))
                }
                continue
            }

            val remoteMillis = remote.timestamp.let { if (it in 1..9_999_999_999L) it * 1000 else it }
            val remotePercent = remote.percentage
            val remoteAhead = remotePercent - localPercent > 0.001
            val localAhead = localPercent - remotePercent > 0.001

            when {
                remoteMillis > row.updatedAt && remoteAhead && remotePercent in 0.0..1.0 ->
                    dao.upsert(row.copy(percent = remotePercent, updatedAt = remoteMillis))

                localAhead ->
                    runCatching {
                        koSync.push(server, row.key, source, localPercent.coerceIn(0.0, 1.0))
                    }
            }
        }
    }

    @Deprecated("Renamed", ReplaceWith("syncWithKoSync()"))
    suspend fun refreshFromKoSync() = syncWithKoSync()

    private suspend fun pushToKoSync(progress: ReadingProgress) {
        val percent = progress.percent ?: return
        val server = serverRepository.get(progress.serverId) ?: return
        if (!koSync.isConfigured(server)) return
        val source = digestSourceFor(progress.serverId, progress.bookId, progress.digestUrl) ?: return
        runCatching { koSync.push(server, progress.key, source, percent.coerceIn(0.0, 1.0)) }
    }

    private suspend fun digestSourceFor(
        serverId: String,
        bookId: String,
        digestUrl: String?,
    ): DigestSource? =
        downloadRepository.localFile(serverId, bookId)?.let { DigestSource.LocalFile(it) }
            ?: digestUrl?.let { DigestSource.Remote(it) }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"
}
