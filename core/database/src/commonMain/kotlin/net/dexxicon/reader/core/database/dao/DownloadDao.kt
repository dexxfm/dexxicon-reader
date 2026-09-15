package net.dexxicon.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.dexxicon.reader.core.database.entity.DownloadEntity

@Dao
interface DownloadDao {

    // Ordered by when it was queued, not last touched, so tiles keep a fixed position while
    // progress ticks. `updatedAt` is the tie-breaker for rows migrated in with createdAt = 0.
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    /** Downloads still queued or running, oldest-queued first — for the progress notification. */
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING') ORDER BY createdAt ASC, updatedAt ASC")
    suspend fun activeDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE key = :key")
    fun observe(key: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE key = :key")
    suspend fun find(key: String): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloaded, " +
            "totalBytes = :total, localPath = :localPath, error = :error, updatedAt = :updatedAt " +
            "WHERE key = :key",
    )
    suspend fun updateState(
        key: String,
        status: String,
        downloaded: Long,
        total: Long?,
        localPath: String?,
        error: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM downloads WHERE key = :key")
    suspend fun deleteByKey(key: String)

    /** issue #206 — server removal cascade; `observeAll()` (Home's Downloaded shelf) has no
     *  server scoping, so a deleted server's downloads — which cache their own title/cover —
     *  would otherwise keep showing up there forever. Returned so the caller can also clean
     *  up each one's on-disk file/queued work before the rows are gone. */
    @Query("SELECT * FROM downloads WHERE serverId = :serverId")
    suspend fun forServer(serverId: String): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    /** issue #206 follow-up — one-time (safe to re-run) sweep for rows orphaned by a server
     *  deleted *before* [deleteForServer] existed, which never got cleaned up. */
    @Query("SELECT * FROM downloads WHERE serverId NOT IN (SELECT id FROM servers)")
    suspend fun findOrphaned(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE serverId NOT IN (SELECT id FROM servers)")
    suspend fun deleteOrphaned()
}
