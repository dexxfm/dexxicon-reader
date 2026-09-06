package net.dexxicon.reader.core.model

data class LibraryItem(
    val id: String,
    val serverId: String,
    val entryId: String,
    val title: String,
    val authors: List<String>,
    val format: ContentFormat,
    val coverUrl: String?,
    val acquisitionUrl: String,
    val mediaType: String,
    val addedAt: Long,
    val download: DownloadState = DownloadState.None,
    val position: ReadingPosition? = null,
)

sealed interface DownloadState {
    data object None : DownloadState
    data class Queued(val progress: Float = 0f) : DownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long?) : DownloadState {
        val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { downloadedBytes.toFloat() / it }
    }
    data class Failed(val reason: String) : DownloadState
    data class Completed(val localPath: String, val sizeBytes: Long) : DownloadState
}

/** Format-agnostic reading position. Exactly one of the payloads is meaningful per format. */
data class ReadingPosition(
    val itemId: String,
    val percentage: Double,
    val locatorJson: String? = null,
    val positionMs: Long? = null,
    val pageIndex: Int? = null,
    val chapterIndex: Int? = null,
    val updatedAt: Long = 0L,
    val syncedAt: Long? = null,
)

data class Annotation(
    val id: String,
    val itemId: String,
    val locatorJson: String,
    val progression: Double,
    val selectedText: String,
    val note: String? = null,
    val color: HighlightColor = HighlightColor.YELLOW,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteId: String? = null,
    val dirty: Boolean = true,
)

enum class HighlightColor { YELLOW, GREEN, BLUE, PINK, PURPLE }
