package net.dexxicon.reader.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.dexxicon.reader.core.model.ContentFormat
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
    val title: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val format: String? = null,
    val digestUrl: String? = null,
    /** issue #214 — true when this row holds a local change the server hasn't confirmed yet.
     *  A server with no real progress timestamp (BookOrbit) can't tell "genuinely more
     *  advanced" from "just hasn't synced" by percent alone, so reconciliation trusts this
     *  flag instead: dirty rows get pushed, clean rows always adopt whatever the server says. */
    val dirty: Boolean = false,
    /** issue #257 — see [ReadingProgress.series]. */
    val series: String? = null,
    val seriesIndex: Double? = null,
    /** issue #251 — see [ReadingProgress.hiddenAtPercent]. */
    val hiddenAtPercent: Double? = null,
) {
    fun toDomain(): ReadingProgress = ReadingProgress(
        serverId = serverId,
        bookId = bookId,
        percent = percent,
        locator = locator,
        updatedAt = updatedAt,
        title = title,
        author = author,
        coverUrl = coverUrl,
        format = format?.let { runCatching { ContentFormat.valueOf(it) }.getOrNull() },
        digestUrl = digestUrl,
        series = series,
        seriesIndex = seriesIndex,
        hiddenAtPercent = hiddenAtPercent,
    )

    companion object {
        fun fromDomain(progress: ReadingProgress): ReadingProgressEntity = ReadingProgressEntity(
            key = progress.key,
            serverId = progress.serverId,
            bookId = progress.bookId,
            percent = progress.percent,
            locator = progress.locator,
            updatedAt = progress.updatedAt,
            title = progress.title,
            author = progress.author,
            coverUrl = progress.coverUrl,
            format = progress.format?.name,
            digestUrl = progress.digestUrl,
            series = progress.series,
            seriesIndex = progress.seriesIndex,
            hiddenAtPercent = progress.hiddenAtPercent,
        )
    }
}
