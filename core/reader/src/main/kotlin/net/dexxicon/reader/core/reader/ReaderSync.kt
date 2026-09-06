package net.dexxicon.reader.core.reader

import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.sync.DigestSource
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin front for the readers/player over KOReader sync — compute the digest once per book,
 * pull a resume point on open, push the percentage as it changes.
 */
@Singleton
class ReaderSync @Inject constructor(
    private val serverRepository: ServerRepository,
    private val koSync: KoSyncRepository,
) {
    /**
     * The percentage the sync server has for this book, if it is meaningfully ahead of
     * [localPercent] — the caller can offer to jump there. Null if sync isn't set up, the
     * server has nothing, or the difference is negligible.
     */
    suspend fun remoteResumePercent(
        serverId: String,
        bookId: String,
        source: DigestSource,
        localPercent: Double?,
    ): Double? {
        val server = serverRepository.get(serverId) ?: return null
        if (!koSync.isConfigured(server)) return null
        val remote = koSync.pull(server, "$serverId::$bookId", source) ?: return null
        val local = localPercent ?: 0.0
        return remote.percentage.takeIf { it - local > 0.01 && it <= 1.0 }
    }

    suspend fun report(serverId: String, bookId: String, source: DigestSource, percent: Double?) {
        val pct = percent ?: return
        val server = serverRepository.get(serverId) ?: return
        if (!koSync.isConfigured(server)) return
        koSync.push(server, "$serverId::$bookId", source, pct)
    }
}
