package net.dexxicon.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE key = :key")
    suspend fun find(key: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE key = :key")
    fun observe(key: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE serverId = :serverId")
    fun observeForServer(serverId: String): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress")
    suspend fun all(): List<ReadingProgressEntity>

    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    /** Books that have been started but not finished, most recently touched first. */
    @Query(
        "SELECT * FROM reading_progress " +
            "WHERE percent IS NOT NULL AND percent > 0.0 AND percent < 0.985 " +
            "ORDER BY updatedAt DESC",
    )
    fun observeInProgress(): Flow<List<ReadingProgressEntity>>

    @Upsert
    suspend fun upsert(entity: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress WHERE key = :key")
    suspend fun deleteByKey(key: String)

    /** issue #206 — server removal cascade. `observeAll()`/`observeInProgress()` (Home's
     *  Continue reading/listening shelves) have no server scoping at all — a deleted server's
     *  progress rows, which cache their own title/cover, would otherwise keep showing up there
     *  forever. */
    @Query("DELETE FROM reading_progress WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    /** issue #206 follow-up — one-time (safe to re-run) sweep for rows orphaned by a server
     *  deleted *before* [deleteForServer] existed, which never got cleaned up. */
    @Query("DELETE FROM reading_progress WHERE serverId NOT IN (SELECT id FROM servers)")
    suspend fun deleteOrphaned()
}
