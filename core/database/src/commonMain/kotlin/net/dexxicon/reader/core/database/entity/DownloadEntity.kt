package net.dexxicon.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val key: String,
    val serverId: String,
    val bookId: String,
    val title: String,
    /** author names, one per line */
    val authors: String,
    val series: String?,
    val coverUrl: String?,
    val format: String,
    val sourceUrl: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val localPath: String?,
    val error: String?,
    val updatedAt: Long,
    /** When the download was first queued. Fixed for the row's life — the list orders by it
     *  so tiles don't jump around as progress changes. */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
) {
    fun toDomain(): Download = Download(
        serverId = serverId,
        bookId = bookId,
        title = title,
        authors = authors.split('\n').filter { it.isNotBlank() },
        series = series,
        coverUrl = coverUrl,
        format = runCatching { ContentFormat.valueOf(format) }.getOrDefault(ContentFormat.UNKNOWN),
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        localPath = localPath,
        error = error,
        updatedAt = updatedAt,
    )

    companion object {
        @OptIn(ExperimentalTime::class)
        fun new(
            serverId: String,
            bookId: String,
            title: String,
            authors: List<String>,
            series: String?,
            coverUrl: String?,
            format: ContentFormat,
            sourceUrl: String,
        ): DownloadEntity = DownloadEntity(
            key = "$serverId::$bookId",
            serverId = serverId,
            bookId = bookId,
            title = title,
            authors = authors.joinToString("\n"),
            series = series,
            coverUrl = coverUrl,
            format = format.name,
            sourceUrl = sourceUrl,
            status = DownloadStatus.QUEUED.name,
            downloadedBytes = 0L,
            totalBytes = null,
            localPath = null,
            error = null,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
    }
}
