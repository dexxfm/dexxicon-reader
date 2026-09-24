package net.dexxicon.reader.core.model

/** An offline copy of one book, in some stage of being fetched. */
data class Download(
    val serverId: String,
    val bookId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    /** issue #282 — the book's place in [series], so the Downloaded shelf can show its badge
     *  without a progress row (a downloaded book that was never opened has none). */
    val seriesIndex: Double? = null,
    val coverUrl: String? = null,
    val format: ContentFormat = ContentFormat.UNKNOWN,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val localPath: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0L,
    /** [AudiobookInfo.durationMs][net.dexxicon.reader.core.model.AudiobookInfo] at the time
     *  this was downloaded — only meaningful for [ContentFormat.AUDIOBOOK]. Captured because
     *  the offline degraded [net.dexxicon.reader.core.model.BookDetail] this reconstructs into
     *  (issue #250) has no server round-trip to pull it from otherwise, and the shared player
     *  screen refuses to show a position against an unknown (zero) duration. */
    val durationMs: Long? = null,
) {
    val key: String get() = "$serverId::$bookId"
    val authorLine: String get() = authors.joinToString(", ")

    /** 0f–1f, or null when the total size isn't known yet. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }

    val isComplete: Boolean get() = status == DownloadStatus.DONE
}

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED }

/** File extension for a locally saved copy of this format. */
val ContentFormat.fileExtension: String
    get() = when (this) {
        ContentFormat.EPUB -> "epub"
        ContentFormat.PDF -> "pdf"
        ContentFormat.COMIC -> "cbz"
        ContentFormat.AUDIOBOOK -> "m4b"
        ContentFormat.MOBI -> "mobi"
        ContentFormat.AZW3 -> "azw3"
        ContentFormat.FB2 -> "fb2"
        ContentFormat.UNKNOWN -> "bin"
    }
