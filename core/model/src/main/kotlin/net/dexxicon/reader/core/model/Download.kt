package net.dexxicon.reader.core.model

/** An offline copy of one book, in some stage of being fetched. */
data class Download(
    val serverId: String,
    val bookId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val coverUrl: String? = null,
    val format: ContentFormat = ContentFormat.UNKNOWN,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val localPath: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0L,
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
