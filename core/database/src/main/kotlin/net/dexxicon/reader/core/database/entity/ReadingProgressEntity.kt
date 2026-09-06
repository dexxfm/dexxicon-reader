package net.dexxicon.reader.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.dexxicon.reader.core.model.ReadingProgress

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    /** "serverId::bookId" */
    @PrimaryKey val key: String,
    val serverId: String,
    val bookId: String,
    val percent: Double?,
    val locator: String?,
    val updatedAt: Long,
) {
    fun toDomain(): ReadingProgress = ReadingProgress(
        serverId = serverId,
        bookId = bookId,
        percent = percent,
        locator = locator,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(progress: ReadingProgress): ReadingProgressEntity = ReadingProgressEntity(
            key = progress.key,
            serverId = progress.serverId,
            bookId = progress.bookId,
            percent = progress.percent,
            locator = progress.locator,
            updatedAt = progress.updatedAt,
        )
    }
}
