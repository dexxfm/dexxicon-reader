package net.dexxicon.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.dexxicon.reader.core.database.entity.BookmarkEntity

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId AND bookId = :bookId AND deleted = 0 ORDER BY progression, createdAt")
    fun observeForBook(serverId: String, bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId AND bookId = :bookId AND deleted = 0 ORDER BY progression, createdAt")
    suspend fun forBook(serverId: String, bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun find(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE dirty = 1 OR deleted = 1")
    suspend fun pending(): List<BookmarkEntity>

    @Upsert
    suspend fun upsert(entity: BookmarkEntity)

    @Query("UPDATE bookmarks SET deleted = 1, dirty = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun hardDelete(id: String)
}
