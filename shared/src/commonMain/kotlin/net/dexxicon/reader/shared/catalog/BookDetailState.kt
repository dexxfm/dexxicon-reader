package net.dexxicon.reader.shared.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader
import net.dexxicon.reader.shared.toBookDetail

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

    /** issue #256 — the rest of this book's series (every server's copy of it, merged), for
     *  the "More in <series>" shelf. Empty until loaded, and for a book in no series. */
    var seriesBooks by mutableStateOf<List<AggregatedBook>>(emptyList())
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
                    loadSeries(result.value)
                    refreshSeriesBadges(result.value)
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
            val server = container.serverRepository.get(sid)
            BookCopy(
                serverId = sid,
                serverName = server?.displayName ?: "Library",
                bookId = bid,
                webUrl = server?.webBookUrl(bid),
            )
        }

    /** issue #282 — Home's Continue and Downloaded shelves show the series badge from what was
     *  stored when the book was opened or downloaded; bring those up to date with the server. */
    private fun refreshSeriesBadges(detail: BookDetail) {
        val s = detail.summary
        if (s.series == null && s.seriesIndex == null) return
        scope.launch {
            runCatching { container.progressRepository.updateSeries(serverId, bookId, s.series, s.seriesIndex) }
            runCatching { container.downloadRepository.updateSeries(serverId, bookId, s.series, s.seriesIndex) }
        }
    }

    private fun loadSeries(detail: BookDetail) {
        val name = detail.summary.series?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            val groups = container.catalogRepository.seriesNamed(name)
            val page = container.catalogRepository.groupBooks(groups, null, BookSort.SERIES, page = 0)
            val books = (page as? Outcome.Success)?.value?.books ?: return@launch
            seriesBooks = books.filterNot { book ->
                book.copies.any { it.serverId == serverId && it.bookId == bookId }
            }
        }
    }

    fun onDownload() {
        val d = detail ?: return
        scope.launch { container.downloadRepository.enqueue(d) }
    }

    fun onRemoveDownload() {
        scope.launch { container.downloadRepository.remove(serverId, bookId) }
    }

    /** issue #259 — whether this device can save a copy of a book file for use outside the
     *  app (Android; see [net.dexxicon.reader.shared.di.BookFileWriter]). */
    val canSaveCopy: Boolean get() = container.bookFileWriter != null

    var savingCopy by mutableStateOf(false)
        private set

    /** issue #259 — a one-off result for Book Detail's snackbar; cleared once shown. */
    var message by mutableStateOf<String?>(null)
        private set

    fun messageShown() {
        message = null
    }

    /** Where the copy button saves to, for its label — "Downloads" or the picked folder's name. */
    val saveCopyDestination: StateFlow<String> = container.appPreferences.preferences
        .map { it.saveCopiesFolderName ?: "Downloads" }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), "Downloads")

    /** issue #259 — saves a copy of the book file to Downloads (or the folder chosen in
     *  Settings). Separate from [onDownload], which keeps the app's own offline copy. */
    fun saveCopy() {
        val d = detail ?: return
        if (savingCopy) return
        savingCopy = true
        scope.launch {
            message = when (val result = container.saveBookToDevice(d, serverId, bookId)) {
                is Outcome.Success -> "Saved “${result.value.fileName}” to ${result.value.location}"
                is Outcome.Failure -> result.error.message ?: "Couldn't save a copy"
            }
            savingCopy = false
        }
    }

    fun setReadingStatus(status: ReadingStatus) {
        val pairs = copies.map { it.serverId to it.bookId }.ifEmpty { listOf(serverId to bookId) }
        container.bookActions.setReadingStatus(pairs, status)
        detail = detail?.copy(readingStatus = status)
    }

    /** Sets the current user's own 1–5 star rating (issue #264) — same optimistic-update
     *  shape as [setReadingStatus]. */
    fun setRating(rating: Int) {
        val pairs = copies.map { it.serverId to it.bookId }.ifEmpty { listOf(serverId to bookId) }
        container.bookActions.setRating(pairs, rating)
        detail = detail?.copy(rating = rating)
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
    ) {
        // issue #250: openReader() became a suspend fun (it now checks
        // DownloadRepository.localFile()) -- this UI click callback isn't itself a coroutine.
        scope.launch { container.openReader(detail, serverId, bookId, onOpenReader) }
    }
}
