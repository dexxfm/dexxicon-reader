package net.dexxicon.reader.shared.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.fileExtension
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader

/**
 * Phase 4 restructure (issue #126) — the one Book Detail state class, replacing *both*
 * native's Hilt `BookDetailViewModel` and `:shared`'s previous ad-hoc `LaunchedEffect`-in-
 * composable fetch. A plain class built from [AppContainer] via
 * `remember(serverId, bookId) { BookDetailState(container, ...) }`, the same pattern every
 * other `:shared` state class ([net.dexxicon.reader.shared.servers.ServersState] etc.) already
 * uses — see [AppContainer]'s own doc comment for why there's no Hilt/DI framework here.
 *
 * [copyIds] is the resolved `(serverId, bookId)` pair for every server carrying this exact
 * book — native's route decodes its own pipe-delimited `copies` nav arg into this *before*
 * constructing this class (routing/encoding stays a platform concern); `:shared`'s own nav
 * graph has no merged-copies concept yet, so it always passes an empty list, which falls back
 * to just `serverId`/`bookId` below, identical to native's own empty-copies fallback.
 */
class BookDetailState(
    private val container: AppContainer,
    val serverId: String,
    val bookId: String,
    copyIds: List<Pair<String, String>>,
    private val scope: CoroutineScope,
) {
    var loading by mutableStateOf(true)
        private set
    var detail by mutableStateOf<BookDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Every server carrying this exact book. Size 1 unless opened from merged Browse. */
    var copies by mutableStateOf<List<BookCopy>>(emptyList())
        private set

    val download: StateFlow<Download?> =
        container.downloadRepository.download(serverId, bookId)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reading position for the Phase 4 (issue #115) progress row on the detail screen — the
     * same [net.dexxicon.reader.core.data.ReadingProgressRepository] the Home shelves already
     * read from, just scoped to this one book. A real capability gain for `:shared` (iOS):
     * this data source didn't exist there before this class replaced the platform-specific
     * fetch logic. */
    val progress: StateFlow<ReadingProgress?> =
        container.progressRepository.observe(serverId, bookId)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        scope.launch {
            copies = resolveCopies(copyIds.ifEmpty { listOf(serverId to bookId) })

            when (val result = container.catalogRepository.detail(serverId, bookId)) {
                is Outcome.Success -> {
                    detail = result.value
                    loading = false
                }
                is Outcome.Failure -> {
                    // Offline? Fall back to what the downloaded copy remembers.
                    val offline = container.downloadRepository.get(serverId, bookId)
                        ?.takeIf { it.status == DownloadStatus.DONE }
                        ?.toBookDetail()
                    if (offline != null) {
                        detail = offline
                    } else {
                        error = result.error.message ?: "Couldn't load this book"
                    }
                    loading = false
                }
            }
        }
    }

    private suspend fun resolveCopies(ids: List<Pair<String, String>>): List<BookCopy> =
        ids.map { (sid, bid) ->
            BookCopy(
                serverId = sid,
                serverName = container.serverRepository.get(sid)?.displayName ?: "Library",
                bookId = bid,
            )
        }

    private fun Download.toBookDetail(): BookDetail = BookDetail(
        summary = BookSummary(
            id = bookId,
            serverId = serverId,
            title = title,
            authors = authors,
            series = series,
            coverUrl = coverUrl,
            format = format,
        ),
        fileExtension = localPath?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
            ?: format.fileExtension,
        fileSizeBytes = totalBytes,
        acquisitions = localPath?.let {
            listOf(
                Acquisition(
                    href = it,
                    mediaType = format.name,
                    format = format,
                    relation = AcquisitionRelation.OPEN_ACCESS,
                ),
            )
        } ?: emptyList(),
    )

    fun onDownload() {
        val d = detail ?: return
        scope.launch { container.downloadRepository.enqueue(d) }
    }

    fun onRemoveDownload() {
        scope.launch { container.downloadRepository.remove(serverId, bookId) }
    }

    fun setReadingStatus(status: ReadingStatus) {
        val pairs = copies.map { it.serverId to it.bookId }.ifEmpty { listOf(serverId to bookId) }
        container.bookActions.setReadingStatus(pairs, status)
        detail = detail?.copy(readingStatus = status)
    }

    /** Whether the offline/download button should show at all — [DownloadRepository
     * .supportsDownloads][net.dexxicon.reader.core.data.download.DownloadRepository.supportsDownloads],
     * not "which app launched this screen" (native `:app` and `:shared` resolve to the same
     * real implementation on Android). */
    val supportsDownloads: Boolean get() = container.downloadRepository.supportsDownloads

    /** Resolves [detail] into an [OnOpenReader] call for [serverId]/[bookId] — see
     * [net.dexxicon.reader.shared.openReader]'s own doc comment (shared with Home's
     * "Continue reading/listening" shelves, issue #130). */
    fun resolveReaderLaunch(
        detail: BookDetail,
        serverId: String,
        bookId: String,
        onOpenReader: OnOpenReader,
    ) = container.openReader(detail, serverId, bookId, onOpenReader)
}
