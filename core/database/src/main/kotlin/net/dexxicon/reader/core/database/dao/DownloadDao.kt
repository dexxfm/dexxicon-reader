package net.dexxicon.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.dexxicon.reader.core.database.entity.DownloadEntity

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

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
