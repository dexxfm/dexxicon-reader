package net.dexxicon.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.dexxicon.reader.core.database.entity.HighlightEntity

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlights WHERE serverId = :serverId AND bookId = :bookId AND deleted = 0 ORDER BY progression")
    fun observeForBook(serverId: String, bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE serverId = :serverId AND bookId = :bookId AND deleted = 0 ORDER BY progression")
    suspend fun forBook(serverId: String, bookId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun find(id: String): HighlightEntity?

    @Query("SELECT * FROM highlights WHERE dirty = 1 OR deleted = 1")
    suspend fun pending(): List<HighlightEntity>

    @Upsert
    suspend fun upsert(entity: HighlightEntity)

    @Query("UPDATE highlights SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun hardDelete(id: String)
}
