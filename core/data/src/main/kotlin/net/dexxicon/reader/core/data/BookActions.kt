package net.dexxicon.reader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The item-level actions offered by the long-press menu on a book: mark read/unread and
 * download/remove. Runs on the application scope so an action started from a card that the
 * user then navigates away from still completes.
 */
@Singleton
class BookActions @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val downloadRepository: DownloadRepository,
    private val progressRepository: ReadingProgressRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Mark a book finished (100 %) or not started (0 %) and push it on the server's sync
     * channel — the servers derive their own read-status from the percentage.
     */
    fun markFinished(serverId: String, bookId: String, finished: Boolean) {
        scope.launch {
            val detail = detailOf(serverId, bookId) ?: return@launch
            val s = detail.summary
            progressRepository.save(
                ReadingProgress(
                    serverId = serverId,
                    bookId = bookId,
                    percent = if (finished) 1.0 else 0.0,
                    format = s.format,
                    title = s.title,
                    author = s.authorLine.takeIf { it.isNotBlank() },
                    coverUrl = s.coverUrl,
                    digestUrl = detail.primaryAcquisition?.href,
                ),
            )
        }
    }

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

    private suspend fun detailOf(serverId: String, bookId: String): BookDetail? =
        (catalogRepository.detail(serverId, bookId) as? Outcome.Success)?.value
}
