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
import net.dexxicon.reader.core.data.sync.LibrarySeeder
import net.dexxicon.reader.core.data.sync.NativeProgress
import net.dexxicon.reader.core.data.sync.NativeProgressSync
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.Server
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores where each book was left off, and keeps that position **two-way** with the server.
 *
 * The sync channel is chosen by server type and they are mutually exclusive:
 *  - **BookOrbit / Grimmory** → the server's own native progress API ([NativeProgressSync]),
 *    the exact one its web reader uses, so positions round-trip with the website.
 *  - **Generic OPDS servers** → the KOReader `kosync` protocol ([KoSyncRepository]).
 */
@Singleton
class ReadingProgressRepository @Inject constructor(
    private val dao: ReadingProgressDao,
    private val koSync: KoSyncRepository,
    private val nativeSync: NativeProgressSync,
    private val librarySeeder: LibrarySeeder,
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

    /** Every progress row, keyed by "serverId::bookId". */
    fun observeAll(): Flow<Map<String, ReadingProgress>> =
        dao.observeAll().map { list -> list.map { it.toDomain() }.associateBy { it.key } }

    suspend fun get(serverId: String, bookId: String): ReadingProgress? = withContext(io) {
        dao.find(key(serverId, bookId))?.toDomain()
    }

    /** True when [server] syncs progress through its native API rather than kosync. */
    private fun usesNative(server: Server): Boolean = nativeSync.supports(server)

    private fun usesKoSync(server: Server): Boolean =
        !usesNative(server) && koSync.isConfigured(server)

    /**
     * Persist a position update. Metadata fields ([ReadingProgress.title] etc.) are merged —
     * a null on [progress] keeps whatever the row already had. When [progress] carries a
     * percentage it is also pushed to the server.
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
            appScope.launch { pushToServer(domain) }
        }
    }

    suspend fun clear(serverId: String, bookId: String) = withContext(io) {
        dao.deleteByKey(key(serverId, bookId))
    }

    /**
     * Fill the Library's "Continue" shelves from [server]'s own in-progress lists (its web
     * app's "continue reading" / "continue listening"). Runs when a server is added and on
     * every [syncProgress]. Only adds books not already tracked here — a local position,
     * which may be newer, is never overwritten.
     */
    suspend fun seedFromServer(serverId: String) = withContext(io) {
        val server = serverRepository.get(serverId) ?: return@withContext
        seedFromServer(server)
    }

    /** Fire-and-forget [seedFromServer] — for right after a server is added. */
    fun seedFromServerAsync(serverId: String) {
        appScope.launch { runCatching { seedFromServer(serverId) } }
    }

    private suspend fun seedFromServer(server: Server) {
        if (!usesNative(server)) return
        val now = System.currentTimeMillis()
        for (row in librarySeeder.inProgress(server)) {
            if (row.percent == null) continue
            if (dao.find(row.key) != null) continue
            dao.upsert(ReadingProgressEntity.fromDomain(row.copy(updatedAt = now)))
        }
    }

    /**
     * The position (ms) an audiobook should resume at: the furthest of the local position and
     * whatever the server has. Never rewinds.
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
        var best = localMs

        if (usesNative(server)) {
            nativeSync.pull(server, bookId, ContentFormat.AUDIOBOOK, digestUrl)?.let { np ->
                val ms = np.positionMs ?: (np.percent.coerceIn(0.0, 1.0) * durationMs).toLong()
                best = maxOf(best, ms)
            }
        } else if (usesKoSync(server)) {
            digestSourceFor(serverId, bookId, digestUrl)?.let { source ->
                runCatching { koSync.pull(server, key(serverId, bookId), source) }.getOrNull()?.let {
                    best = maxOf(best, (it.percentage.coerceIn(0.0, 1.0) * durationMs).toLong())
                }
            }
        }
        best
    }

    /**
     * The server's position when it is meaningfully ahead of [localPercent] — for the
     * comic / PDF readers' "Continue from page N" prompt. Native-sync servers only;
     * carries the exact page when the server has one.
     */
    suspend fun nativeRemoteAhead(
        serverId: String,
        bookId: String,
        format: ContentFormat,
        digestUrl: String?,
        localPercent: Double?,
    ): NativeProgress? = withContext(io) {
        val server = serverRepository.get(serverId) ?: return@withContext null
        if (!usesNative(server)) return@withContext null
        val local = localPercent ?: 0.0
        nativeSync.pull(server, bookId, format, digestUrl)
            ?.takeIf { it.percent - local > 0.01 && it.percent <= 1.0 }
    }

    /**
     * A resume percentage the server has that is meaningfully ahead of [localPercent] — for
     * the readers' "Continue from NN%" prompt.
     */
    suspend fun remoteResumePercent(
        serverId: String,
        bookId: String,
        format: ContentFormat,
        digestUrl: String?,
        localPercent: Double?,
    ): Double? = withContext(io) {
        val server = serverRepository.get(serverId) ?: return@withContext null
        val local = localPercent ?: 0.0
        var best: Double? = null

        if (usesNative(server)) {
            nativeSync.pull(server, bookId, format, digestUrl)?.percent?.let { p ->
                if (p - local > 0.01 && p <= 1.0) best = p
            }
        } else if (usesKoSync(server)) {
            digestSourceFor(serverId, bookId, digestUrl)?.let { source ->
                runCatching { koSync.pull(server, key(serverId, bookId), source) }.getOrNull()
                    ?.percentage?.let { p -> if (p - local > 0.01 && p <= 1.0) best = p }
            }
        }
        best
    }

    /**
     * Reconcile every in-progress book with its server **both ways** on that server's channel:
     * adopt the server's position when newer, push ours when newer or when the server has
     * nothing yet.
     */
    suspend fun syncProgress() = withContext(io) {
        val allServers = serverRepository.servers.first()

        // Touch every configured kosync (generic OPDS) account so each records a "last synced"
        // time even with nothing in progress right now.
        allServers
            .filter { usesKoSync(it) }
            .forEach { server -> runCatching { koSync.verify(server) } }

        // Pull each native server's own "continue" lists so books in progress elsewhere show
        // up on the Library shelves even if they were never opened in this app.
        allServers
            .filter { usesNative(it) }
            .forEach { server -> runCatching { seedFromServer(server) } }

        val rows = dao.all()
        val servers = rows.map { it.serverId }.distinct()
            .mapNotNull { id -> serverRepository.get(id)?.let { id to it } }
            .toMap()

        for (row in rows) {
            val server = servers[row.serverId] ?: continue
            val localPercent = row.percent ?: continue
            val format = row.format?.let { runCatching { ContentFormat.valueOf(it) }.getOrNull() }
                ?: ContentFormat.EPUB
            val domain = row.toDomain()

            if (usesNative(server)) {
                val native = nativeSync.pull(server, row.bookId, format, row.digestUrl)
                when {
                    native == null || localPercent - native.percent > 0.005 -> runCatching {
                        nativeSync.push(server, row.bookId, format, row.digestUrl, localPercent, positionMsOf(domain), pageOf(domain))
                    }
                    shouldAdoptNative(native, row) -> {
                        // Adopt the server's %, and — for comics/PDF where it also sent an
                        // exact page — move the local position so reopening resumes there.
                        val locator = native.page
                            ?.takeIf { format == ContentFormat.COMIC || format == ContentFormat.PDF }
                            ?.let { locatorAtPage(it, native.percent, format) }
                            ?: row.locator
                        dao.upsert(
                            row.copy(
                                percent = native.percent,
                                locator = locator,
                                updatedAt = native.updatedAtMillis ?: row.updatedAt,
                            ),
                        )
                    }
                }
            } else if (usesKoSync(server)) {
                val source = digestSourceFor(row.serverId, row.bookId, row.digestUrl) ?: continue
                val remote = runCatching { koSync.pull(server, row.key, source) }.getOrNull()
                if (remote == null) {
                    runCatching { koSync.push(server, row.key, source, localPercent.coerceIn(0.0, 1.0)) }
                } else {
                    val remoteMillis = remote.timestamp.let { if (it in 1..9_999_999_999L) it * 1000 else it }
                    val remoteAhead = remote.percentage - localPercent > 0.001
                    when {
                        remoteMillis > row.updatedAt && remoteAhead && remote.percentage in 0.0..1.0 ->
                            dao.upsert(row.copy(percent = remote.percentage, updatedAt = remoteMillis))
                        localPercent - remote.percentage > 0.001 ->
                            runCatching { koSync.push(server, row.key, source, localPercent.coerceIn(0.0, 1.0)) }
                    }
                }
            }
        }
    }

    @Deprecated("Renamed", ReplaceWith("syncProgress()"))
    suspend fun syncWithKoSync() = syncProgress()

    private fun shouldAdoptNative(native: NativeProgress, row: ReadingProgressEntity): Boolean {
        val local = row.percent ?: 0.0
        if (native.percent - local <= 0.005) return false
        return native.updatedAtMillis == null || native.updatedAtMillis > row.updatedAt
    }

    private suspend fun pushToServer(progress: ReadingProgress) {
        val percent = progress.percent ?: return
        val server = serverRepository.get(progress.serverId) ?: return
        val format = progress.format ?: ContentFormat.EPUB

        if (usesNative(server)) {
            runCatching {
                nativeSync.push(
                    server, progress.bookId, format, progress.digestUrl,
                    percent, positionMsOf(progress), pageOf(progress),
                )
            }
        } else if (usesKoSync(server)) {
            digestSourceFor(progress.serverId, progress.bookId, progress.digestUrl)?.let { source ->
                runCatching { koSync.push(server, progress.key, source, percent.coerceIn(0.0, 1.0)) }
            }
        }
    }

    /** Audiobook position in ms, parsed from the `{"position":<ms>}` locator. */
    private fun positionMsOf(progress: ReadingProgress): Long? {
        if (progress.format != ContentFormat.AUDIOBOOK) return null
        val loc = progress.locator ?: return null
        return runCatching { JSONObject(loc).optLong("position", 0L).takeIf { it > 0L } }.getOrNull()
    }

    /**
     * Comic / PDF 1-based page as a plain string, parsed from the Readium locator's
     * `locations.position`. The native servers store this as an integer, so it must be
     * clean — anything else is dropped (they `Integer.parseInt` it).
     */
    private fun pageOf(progress: ReadingProgress): String? {
        if (progress.format != ContentFormat.COMIC && progress.format != ContentFormat.PDF) return null
        val loc = progress.locator ?: return null
        return runCatching {
            JSONObject(loc).optJSONObject("locations")?.optInt("position", 0)?.takeIf { it > 0 }?.toString()
        }.getOrNull()
    }

    /**
     * A comic/PDF locator pointing at [page]. The `href` is a placeholder — the reader
     * re-resolves the page against the open publication's reading order, so a stale href
     * from the previous position can't win. Kept valid so `Locator.fromJSON` accepts it.
     */
    private fun locatorAtPage(page: Int, percent: Double, format: ContentFormat): String =
        JSONObject()
            .put("href", "sync")
            .put("type", if (format == ContentFormat.PDF) "application/pdf" else "image/jpeg")
            .put(
                "locations",
                JSONObject().put("position", page).put("totalProgression", percent.coerceIn(0.0, 1.0)),
            )
            .toString()

    private suspend fun digestSourceFor(
        serverId: String,
        bookId: String,
        digestUrl: String?,
    ): DigestSource? =
        downloadRepository.localFile(serverId, bookId)?.let { DigestSource.LocalFile(it) }
            ?: digestUrl?.let { DigestSource.Remote(it) }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"
}
