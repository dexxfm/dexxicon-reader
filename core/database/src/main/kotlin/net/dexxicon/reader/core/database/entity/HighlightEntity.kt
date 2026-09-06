package net.dexxicon.reader.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.HighlightColor

@Entity(
    tableName = "highlights",
    indices = [Index("serverId", "bookId")],
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val bookId: String,
    val locatorJson: String,
    val progression: Double,
    val text: String,
    val note: String?,
    val color: String,
    val chapterTitle: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteId: String?,
    val dirty: Boolean,
    val deleted: Boolean = false,
) {
    fun toDomain(): Highlight = Highlight(
        id = id,
        serverId = serverId,
        bookId = bookId,
        locatorJson = locatorJson,
        progression = progression,
        text = text,
        note = note,
        color = runCatching { HighlightColor.valueOf(color) }.getOrDefault(HighlightColor.YELLOW),
        chapterTitle = chapterTitle,
        createdAt = createdAt,
        updatedAt = updatedAt,
        remoteId = remoteId,
        dirty = dirty,
    )

    companion object {
        fun fromDomain(h: Highlight, deleted: Boolean = false): HighlightEntity = HighlightEntity(
            id = h.id,
            serverId = h.serverId,
            bookId = h.bookId,
            locatorJson = h.locatorJson,
            progression = h.progression,
            text = h.text,
            note = h.note,
            color = h.color.name,
            chapterTitle = h.chapterTitle,
            createdAt = h.createdAt,
            updatedAt = h.updatedAt,
            remoteId = h.remoteId,
            dirty = h.dirty,
            deleted = deleted,
        )
    }
}
