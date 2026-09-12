package net.dexxicon.reader.core.data.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.Download

/**
 * Phase 4 restructure (issue #126) — commonMain interface over what was a purely
 * `androidMain`-only `WorkManager`-backed class. `AndroidDownloadRepository` (androidMain) is
 * that same implementation, unchanged, still Hilt-injectable exactly as before (it never
 * moved out of androidMain, so `@Inject`/`@Singleton` still work there without needing an
 * `:app`-hosted `@Provides` the way the fully-portable sync classes do — `:app`'s Hilt module
 * just needs one `@Binds` from the concrete class to this interface).
 *
 * `IosDownloadRepository` is an honest "not supported yet" stub — no iOS equivalent of
 * `WorkManager`-backed background downloads exists in this app today; real iOS download
 * support is separate, later work. [localFile] returns a plain path `String` rather than
 * `java.io.File` (not available on Kotlin/Native) — callers that need to read from it (e.g.
 * `ReadingProgressRepository`'s KOReader digest) already work with paths via
 * [net.dexxicon.reader.core.data.sync.DigestSource.LocalFile].
 */
interface DownloadRepository {
    /** False only on iOS today — lets shared UI (`BookDetailContent`) hide/disable the
     * offline button by platform capability rather than by "which app launched this
     * screen," since native `:app` and `:shared` both resolve to the same real
     * `AndroidDownloadRepository` on Android. */
    val supportsDownloads: Boolean

    val downloads: Flow<List<Download>>

    /** Approximate bytes held by downloads (done + in flight). */
    val usedBytes: Flow<Long>

    /** User-facing notices from download attempts (e.g. the storage limit was hit, or —
     * iOS — downloads aren't supported yet). */
    val messages: SharedFlow<String>

    fun download(serverId: String, bookId: String): Flow<Download?>
    suspend fun get(serverId: String, bookId: String): Download?

    /** Queue (or re-queue) an offline copy of [detail]. */
    suspend fun enqueue(detail: BookDetail): EnqueueResult
    suspend fun remove(serverId: String, bookId: String)

    /** The on-disk path for a completed download, or null. */
    suspend fun localFile(serverId: String, bookId: String): String?
}

/** Outcome of [DownloadRepository.enqueue]. */
sealed interface EnqueueResult {
    data object Queued : EnqueueResult

    /** Nothing downloadable on the book (or — iOS — downloads aren't supported yet). */
    data object NoFile : EnqueueResult

    /** The download would push total downloaded media past the storage limit. */
    data class OverLimit(
        val neededBytes: Long,
        val usedBytes: Long,
        val limitBytes: Long,
    ) : EnqueueResult
}
