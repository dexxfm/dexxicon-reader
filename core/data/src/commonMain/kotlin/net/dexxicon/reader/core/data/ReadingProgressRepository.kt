package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.currentTimeMillis
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.sync.DigestSource
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import net.dexxicon.reader.core.data.sync.LibrarySeeder
import net.dexxicon.reader.core.data.sync.NativeProgress
import net.dexxicon.reader.core.data.sync.NativeProgressSync
import net.dexxicon.reader.core.data.sync.ServerSyncFailure
import net.dexxicon.reader.core.data.sync.SyncFailureReason
import net.dexxicon.reader.core.data.sync.SyncReport
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType

/** issue #154 — [ReadingProgressRepository.syncProgress] reconciles every locally-tracked row
 * against its server; a library with hundreds of read/finished books used to do that one row
 * at a time. This bounds how many rows sync concurrently, so a large row count still finishes
 * quickly without opening hundreds of simultaneous connections to (often) the same server. */
private const val PROGRESS_SYNC_CONCURRENCY = 8

/** issue #251 — cap on existence checks per server per pass; the stale-row case is a handful
 *  of books, and anything past this gets its turn on the next sync. */
internal const val MAX_PRUNE_CHECKS = 10

/**
 * issue #251 — which of [serverId]'s rows [ReadingProgressRepository.pruneGone] should check:
 * in progress, not in the server's own continue lists ([seen]), no unconfirmed local change
 * (`dirty`), and not downloaded ([downloadedKeys]). Pure, so it's unit-tested on its own.
 */
internal fun pruneCandidates(
    rows: List<ReadingProgressEntity>,
    serverId: String,
    seen: Set<String>,
    downloadedKeys: Set<String>,
): List<ReadingProgressEntity> = rows
    .filter { it.serverId == serverId && !it.dirty && it.bookId !in seen && it.key !in downloadedKeys }
    .filter { row -> (row.percent ?: 0.0).let { it > 0.0 && it < 0.985 } }
    .take(MAX_PRUNE_CHECKS)

/**
 * Stores where each book was left off, and keeps that position **two-way** with the server.
 *
 * The sync channel is chosen by server type and they are mutually exclusive:
 *  - **BookOrbit / Grimmory** → the server's own native progress API ([NativeProgressSync]),
 *    the exact one its web reader uses, so positions round-trip with the website.
 *  - **Generic OPDS servers** → the KOReader `kosync` protocol ([KoSyncRepository]).
 *
 * Phase 4 restructure (issue #126) — moved to commonMain; see [NativeProgressSync]'s doc
 * comment for the `@Inject`/`@Singleton` -> `:app`-hosted `@Provides` reasoning. Locator JSON
 * (audiobook position, comic/PDF page) is now built with `kotlinx.serialization.json` instead
 * of Android's bundled `org.json.JSONObject`, which isn't available on iOS.
 */
class ReadingProgressRepository(
    private val dao: ReadingProgressDao,
    private val koSync: KoSyncRepository,
    private val nativeSync: NativeProgressSync,
    private val librarySeeder: LibrarySeeder,
    private val serverRepository: ServerRepository,
    private val tokenManager: TokenManager,
    private val downloadRepository: DownloadRepository,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : ProgressSeeder {
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
                updatedAt = currentTimeMillis(),
                title = progress.title ?: existing?.title,
                author = progress.author ?: existing?.author,
                coverUrl = progress.coverUrl ?: existing?.coverUrl,
                format = progress.format
                    ?: existing?.format?.let {
                        runCatching { ContentFormat.valueOf(it) }.getOrNull()
                    },
                digestUrl = progress.digestUrl ?: existing?.digestUrl,
                series = progress.series ?: existing?.series,
                seriesIndex = progress.seriesIndex ?: existing?.seriesIndex,
                percent = progress.percent ?: existing?.percent,
                locator = progress.locator ?: existing?.locator,
            ),
            // issue #214 — a fresh local write until the push below actually confirms it.
        ).copy(dirty = true)
        dao.upsert(merged)

        if (progress.percent != null) {
            val domain = merged.toDomain()
            appScope.launch { pushToServer(merged.key, merged.updatedAt, domain) }
        }
    }

    /** issue #251 — "Remove from Continue reading". Nothing changes on the server; the book
     *  just stays off the Continue shelves until it's read further (see [ReadingProgress.isHiddenFromContinue]). */
    suspend fun hideFromContinue(serverId: String, bookId: String) = withContext(io) {
        val row = dao.find(key(serverId, bookId)) ?: return@withContext
        dao.upsert(row.copy(hiddenAtPercent = row.percent ?: 0.0))
    }

    /** Undo for [hideFromContinue]. */
    suspend fun unhideFromContinue(serverId: String, bookId: String) = withContext(io) {
        val row = dao.find(key(serverId, bookId)) ?: return@withContext
        dao.upsert(row.copy(hiddenAtPercent = null))
    }

    /**
     * issue #251 — drops progress rows for books that no longer exist on [serverId]. A book
     * deleted, or re-imported under a new id, server-side left its old row behind forever: the
     * seeder adds the new id's row, nothing removes the old one, and Home showed the book twice
     * (the stale copy's tap failing on a 404).
     *
     * Only rows the server *didn't* list in this pass's continue lists ([seen]) are checked,
     * so a normal sync costs nothing extra; each is confirmed gone via [isGone] (a real 404)
     * before anything is deleted. Rows with an unconfirmed local change, or an offline copy
     * that can still be read, are left alone. Returns how many rows were removed.
     */
    suspend fun pruneGone(
        serverId: String,
        seen: Set<String>,
        isGone: suspend (bookId: String) -> Boolean,
    ): Int = withContext(io) {
        val rows = dao.all()
        val downloaded = rows
            .filter { downloadRepository.get(it.serverId, it.bookId)?.status == DownloadStatus.DONE }
            .map { it.key }
            .toSet()
        var removed = 0
        for (row in pruneCandidates(rows, serverId, seen, downloaded)) {
            if (isGone(row.bookId)) {
                dao.deleteByKey(row.key)
                removed++
            }
        }
        removed
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
    override fun seedFromServerAsync(serverId: String) {
        appScope.launch { runCatching { seedFromServer(serverId) } }
    }

    private suspend fun seedFromServer(server: Server) {
        if (!usesNative(server)) return
        persistSeeded(server, librarySeeder.inProgress(server))
    }

    /**
     * Persist newly-seen "continue" rows. A book already tracked here is left alone — its
     * local position may be newer than the server's list.
     *
     * issue #211 — stamping local `now()` for every row makes "recency" really mean
     * "whenever this device happened to first see the book", which differs device to
     * device. Grimmory's per-book progress endpoint has a real server-side timestamp
     * ([NativeProgressSync.pull]'s `updatedAtMillis`, already parsed from `lastReadTime`/
     * per-format `updatedAt`) — fetched here per row so seeded rows sort consistently
     * across devices. BookOrbit has no equivalent field anywhere in its API (investigated
     * for #211) — its rows keep the local-seed-time fallback.
     */
    private suspend fun persistSeeded(server: Server, rows: List<ReadingProgress>) {
        val now = currentTimeMillis()
        backfillSeries(rows)
        val eligible = rows.filter { it.percent != null && dao.find(it.key) == null }
        if (eligible.isEmpty()) return

        val realUpdatedAt: Map<String, Long> = if (server.type == ServerType.GRIMMORY) {
            eligible.chunked(PROGRESS_SYNC_CONCURRENCY).flatMap { chunk ->
                coroutineScope {
                    chunk.map { row ->
                        async {
                            val format = row.format ?: ContentFormat.EPUB
                            row.key to nativeSync.pull(server, row.bookId, format, row.digestUrl)?.updatedAtMillis
                        }
                    }.awaitAll()
                }
            }.mapNotNull { (key, at) -> at?.let { key to it } }.toMap()
        } else {
            emptyMap()
        }

        for (row in eligible) {
            val updatedAt = realUpdatedAt[row.key] ?: now
            dao.upsert(ReadingProgressEntity.fromDomain(row.copy(updatedAt = updatedAt)))
        }
    }

    /**
     * issue #257 — rows tracked before series numbers were cached (or first seen by a reader
     * that didn't know the series) pick it up from the server's own "continue" list, without
     * touching anything else about the row — position, recency and `dirty` all stay exactly
     * as they were.
     */
    private suspend fun backfillSeries(rows: List<ReadingProgress>) {
        for (row in rows) {
            if (row.series == null && row.seriesIndex == null) continue
            val existing = dao.find(row.key) ?: continue
            if (existing.series != null || existing.seriesIndex != null) continue
            dao.upsert(existing.copy(series = row.series, seriesIndex = row.seriesIndex))
        }
    }

    /**
     * The position (ms) an audiobook should resume at: the server's, when it's reachable and
     * has one — the server is the cross-device source of truth, and that includes reflecting
     * an explicit reset (issue #216; before this it was "furthest of local and server, never
     * rewinds", which is exactly what let a stale local cache silently outrun a reset). Falls
     * back to [localMs] only when the server can't be reached at all, or has nothing recorded.
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

        if (usesNative(server)) {
            nativeAudiobookPositionMs(serverId, bookId, digestUrl, durationMs)?.let { return@withContext it }
        } else if (usesKoSync(server)) {
            digestSourceFor(serverId, bookId, digestUrl)?.let { source ->
                runCatching { koSync.pull(server, key(serverId, bookId), source) }.getOrNull()?.let {
                    return@withContext (it.percentage.coerceIn(0.0, 1.0) * durationMs).toLong()
                }
            }
        }
        localMs
    }

    /**
     * issue #216 — the server's raw audiobook position (ms), **not** blended with local like
     * [audiobookResumeMs]'s "furthest wins, never rewind" — so a caller that needs to know
     * whether the two genuinely *disagree* (rather than just picking one silently) can compare
     * this against whatever position it already has. Null when the server has nothing, or the
     * pull fails.
     */
    suspend fun nativeAudiobookPositionMs(
        serverId: String,
        bookId: String,
        digestUrl: String?,
        durationMs: Long,
    ): Long? = withContext(io) {
        if (durationMs <= 0L) return@withContext null
        val server = serverRepository.get(serverId)?.takeIf { usesNative(it) } ?: return@withContext null
        nativeSync.pull(server, bookId, ContentFormat.AUDIOBOOK, digestUrl)?.let { np ->
            np.positionMs ?: (np.percent.coerceIn(0.0, 1.0) * durationMs).toLong()
        }
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
     * nothing yet. The returned [SyncReport] names any server that couldn't be reached.
     */
    suspend fun syncProgress(): SyncReport = withContext(io) {
        val at = currentTimeMillis()
        val allServers = serverRepository.servers.first()
        val failures = mutableListOf<ServerSyncFailure>()
        val seen = mutableMapOf<String, Set<String>>()

        // Renew any session that's close to expiring before it's needed below — cheap when
        // the token is still fresh, and it keeps idle OIDC refresh tokens alive.
        allServers.forEach { server -> runCatching { tokenManager.refreshIfStale(server) } }

        // Touch every configured kosync (generic OPDS) account so each records a "last synced"
        // time even with nothing in progress right now.
        allServers
            .filter { usesKoSync(it) }
            .forEach { server -> runCatching { koSync.verify(server) } }

        // Pull each native server's own "continue" lists so books in progress elsewhere show
        // up on the Library shelves even if they were never opened in this app. This is also
        // the reachability probe for the pass — a failure here means we skip that server's
        // rows below rather than hammering it, and report it.
        allServers.filter { usesNative(it) }.forEach { server ->
            when (val result = librarySeeder.inProgressResult(server)) {
                is Outcome.Success -> {
                    persistSeeded(server, result.value)
                    seen[server.id] = result.value.map { it.bookId }.toSet()
                }
                is Outcome.Failure -> failures += ServerSyncFailure(
                    server.id, server.displayName, result.error.toSyncReason(),
                )
            }
        }
        val failedIds = failures.mapTo(mutableSetOf()) { it.serverId }

        val rows = dao.all()
        val servers = rows.map { it.serverId }.distinct()
            .mapNotNull { id -> serverRepository.get(id)?.let { id to it } }
            .toMap()

        // issue #154 — reconcile rows PROGRESS_SYNC_CONCURRENCY at a time rather than one at a
        // time; each row is an independent network round-trip (or two), so a library with
        // hundreds of tracked rows no longer syncs them fully serially.
        rows.chunked(PROGRESS_SYNC_CONCURRENCY).forEach { chunk ->
            coroutineScope {
                chunk.map { row -> async { reconcileRow(row, servers[row.serverId], failedIds) } }.awaitAll()
            }
        }

        SyncReport(at = at, failures = failures, seenOnServer = seen)
    }

    /** One [row]'s share of [syncProgress]'s reconciliation pass — pulled out so it can run
     * concurrently with other rows' instead of blocking the whole sync on each one in turn. */
    private suspend fun reconcileRow(
        row: ReadingProgressEntity,
        server: Server?,
        failedIds: Set<String>,
    ) {
        if (server == null || server.id in failedIds) return
        val localPercent = row.percent ?: return
        val format = row.format?.let { runCatching { ContentFormat.valueOf(it) }.getOrNull() }
            ?: ContentFormat.EPUB
        val domain = row.toDomain()

        if (usesNative(server)) {
            // issue #214 — a server with no real progress timestamp (BookOrbit) can't tell
            // "genuinely more advanced" from "just hasn't synced" by comparing percentages;
            // an explicit reset (mark unread) *lowers* percent, and the old percent-diff
            // heuristic read that as every other device being "ahead" and pushed their stale
            // cache right back over it. `dirty` is real information a clock isn't: it's set
            // the moment this device makes a local change, and cleared only once the push
            // actually lands — so a dirty row still fights for its change, and a clean one
            // (nothing unconfirmed here) simply trusts whatever the server currently says.
            if (row.dirty) {
                val pushed = nativeSync.push(
                    server, row.bookId, format, row.digestUrl, localPercent, positionMsOf(domain), pageOf(domain),
                )
                if (pushed) dao.clearDirtyIfUnchanged(row.key, row.updatedAt)
            } else {
                val native = nativeSync.pull(server, row.bookId, format, row.digestUrl)
                if (native != null && kotlin.math.abs(native.percent - localPercent) > 0.005) {
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
            val source = digestSourceFor(row.serverId, row.bookId, row.digestUrl) ?: return
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

    @Deprecated("Renamed", ReplaceWith("syncProgress()"))
    suspend fun syncWithKoSync() {
        syncProgress()
    }

    private fun DexxiconError.toSyncReason(): SyncFailureReason = when (this) {
        is DexxiconError.Network -> SyncFailureReason.OFFLINE
        is DexxiconError.Unauthorized -> SyncFailureReason.SIGN_IN_REQUIRED
        else -> SyncFailureReason.SERVER_ERROR
    }

    /** issue #214 — clears [key]'s dirty flag once the push for the write stamped [updatedAt]
     *  actually lands. Takes the key/timestamp separately from [progress] (rather than just
     *  re-deriving them from it) so [dao.clearDirtyIfUnchanged] pins the exact write this call
     *  is confirming, even though this runs on [appScope] after [save] has already returned
     *  and a newer write could have landed in the meantime. */
    private suspend fun pushToServer(key: String, updatedAt: Long, progress: ReadingProgress) {
        val percent = progress.percent ?: return
        val server = serverRepository.get(progress.serverId) ?: return
        val format = progress.format ?: ContentFormat.EPUB

        val pushed = if (usesNative(server)) {
            nativeSync.push(
                server, progress.bookId, format, progress.digestUrl,
                percent, positionMsOf(progress), pageOf(progress),
            )
        } else if (usesKoSync(server)) {
            digestSourceFor(progress.serverId, progress.bookId, progress.digestUrl)?.let { source ->
                runCatching { koSync.push(server, progress.key, source, percent.coerceIn(0.0, 1.0)) }.isSuccess
            } ?: false
        } else {
            false
        }
        if (pushed) dao.clearDirtyIfUnchanged(key, updatedAt)
    }

    /** Audiobook position in ms, parsed from the `{"position":<ms>}` locator. */
    private fun positionMsOf(progress: ReadingProgress): Long? {
        if (progress.format != ContentFormat.AUDIOBOOK) return null
        val loc = progress.locator ?: return null
        return runCatching {
            Json.parseToJsonElement(loc).jsonObject["position"]?.jsonPrimitive?.long?.takeIf { it > 0L }
        }.getOrNull()
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
            Json.parseToJsonElement(loc).jsonObject["locations"]?.jsonObject?.get("position")
                ?.jsonPrimitive?.int?.takeIf { it > 0 }?.toString()
        }.getOrNull()
    }

    /**
     * A comic/PDF locator pointing at [page]. The `href` is a placeholder — the reader
     * re-resolves the page against the open publication's reading order, so a stale href
     * from the previous position can't win. Kept valid so `Locator.fromJSON` accepts it.
     */
    private fun locatorAtPage(page: Int, percent: Double, format: ContentFormat): String =
        buildJsonObject {
            put("href", "sync")
            put("type", if (format == ContentFormat.PDF) "application/pdf" else "image/jpeg")
            put(
                "locations",
                buildJsonObject {
                    put("position", page)
                    put("totalProgression", percent.coerceIn(0.0, 1.0))
                },
            )
        }.toString()

    private suspend fun digestSourceFor(
        serverId: String,
        bookId: String,
        digestUrl: String?,
    ): DigestSource? =
        downloadRepository.localFile(serverId, bookId)?.let { DigestSource.LocalFile(it) }
            ?: digestUrl?.let { DigestSource.Remote(it) }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"
}
