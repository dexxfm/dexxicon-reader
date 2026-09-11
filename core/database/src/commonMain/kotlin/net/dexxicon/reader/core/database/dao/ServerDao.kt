package net.dexxicon.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.dexxicon.reader.core.database.entity.ServerEntity

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observe(id: String): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun findById(id: String): ServerEntity?

    @Query("SELECT COUNT(*) FROM servers")
    fun count(): Flow<Int>

    @Upsert
    suspend fun upsert(server: ServerEntity)

    @Delete
    suspend fun delete(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE servers SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)

    /** Persist a full ordering: element index becomes each server's [ServerEntity.sortOrder]. */
    @Transaction
    suspend fun applyOrder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}
