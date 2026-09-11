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
}
