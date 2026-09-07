package net.dexxicon.reader.core.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.sync.NativeProgressSync
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The item-level actions offered by the long-press menu on a book: set reading status,
 * mark read/unread and download/remove. Runs on the application scope so an action started
 * from a card that the user then navigates away from still completes.
 */
@Singleton
class BookActions @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val downloadRepository: DownloadRepository,
    private val progressRepository: ReadingProgressRepository,
    private val serverRepository: ServerRepository,
    private val nativeProgressSync: NativeProgressSync,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Set the server's per-user reading status. **Read** / **Unread** also nudge the
     * reading percentage to 100 % / 0 % so the position and the status attribute agree.
     */
    fun setReadingStatus(serverId: String, bookId: String, status: ReadingStatus) {
        scope.launch {
            val server = serverRepository.get(serverId) ?: return@launch
            runCatching { nativeProgressSync.pushStatus(server, bookId, status) }
                .onFailure { Log.w("BookActions", "set status failed: ${it.message}") }
            when (status) {
                ReadingStatus.READ -> pushProgress(serverId, bookId, 1.0)
                ReadingStatus.UNREAD -> pushProgress(serverId, bookId, 0.0)
                else -> {}
            }
        }
    }

    /** "Mark as read" / "Mark as unread" — the quick toggle; also sets the status. */
    fun markFinished(serverId: String, bookId: String, finished: Boolean) =
        setReadingStatus(serverId, bookId, if (finished) ReadingStatus.READ else ReadingStatus.UNREAD)

    /** Queue an offline copy, or remove one — [currentStatus] `null` means not downloaded. */
    fun downloadOrRemove(serverId: String, bookId: String, currentStatus: DownloadStatus?) {
        scope.launch {
            if (currentStatus == null) {
                detailOf(serverId, bookId)?.let { downloadRepository.enqueue(it) }
            } else {
                downloadRepository.remove(serverId, bookId)
            }
        }
    }

    private suspend fun pushProgress(serverId: String, bookId: String, percent: Double) {
        val detail = detailOf(serverId, bookId) ?: return
        val s = detail.summary
        progressRepository.save(
            ReadingProgress(
                serverId = serverId,
                bookId = bookId,
                percent = percent,
                format = s.format,
                title = s.title,
                author = s.authorLine.takeIf { it.isNotBlank() },
                coverUrl = s.coverUrl,
                digestUrl = detail.primaryAcquisition?.href,
            ),
        )
    }

    private suspend fun detailOf(serverId: String, bookId: String): BookDetail? =
        (catalogRepository.detail(serverId, bookId) as? Outcome.Success)?.value
}
