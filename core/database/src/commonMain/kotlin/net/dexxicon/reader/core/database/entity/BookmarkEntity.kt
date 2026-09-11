package net.dexxicon.reader.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.dexxicon.reader.core.model.Bookmark

@Entity(
    tableName = "bookmarks",
    indices = [Index("serverId", "bookId")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val bookId: String,
    val locatorJson: String,
    val progression: Double,
    val title: String,
    val foreignCfi: String?,
    val createdAt: Long,
    val remoteId: String?,
    val dirty: Boolean,
    val deleted: Boolean = false,
) {
    fun toDomain(): Bookmark = Bookmark(
        id = id,
        serverId = serverId,
        bookId = bookId,
        locatorJson = locatorJson,
        progression = progression,
        title = title,
        foreignCfi = foreignCfi,
        createdAt = createdAt,
        remoteId = remoteId,
        dirty = dirty,
    )

    companion object {
        fun fromDomain(b: Bookmark, deleted: Boolean = false): BookmarkEntity = BookmarkEntity(
            id = b.id,
            serverId = b.serverId,
            bookId = b.bookId,
            locatorJson = b.locatorJson,
            progression = b.progression,
            title = b.title,
            foreignCfi = b.foreignCfi,
            createdAt = b.createdAt,
            remoteId = b.remoteId,
            dirty = b.dirty,
            deleted = deleted,
        )
    }
}
