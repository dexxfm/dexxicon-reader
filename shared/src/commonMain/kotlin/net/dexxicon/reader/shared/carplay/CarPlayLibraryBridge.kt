package net.dexxicon.reader.shared.carplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.Chapter
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader

/**
 * issue #240 — CarPlay's library browse tree (parity with Android Auto's own
 * `MediaLibraryContentSource`/`MediaLibraryContentSourceImpl`, issue #118), exposed the same
 * closure-based way [net.dexxicon.reader.shared.reader.AudiobookProgressSync] already is —
 * see that class's own doc comment for why plain closures, not `suspend fun` exports.
 *
 * Deliberately NOT a port of `MediaLibraryContentSourceImpl` itself (that class is Android-only,
 * in `:core:data`'s `androidMain` source set, and leans on `android.net.Uri`/`java.io.File`) —
 * this reimplements its query logic directly against the same already-commonMain repositories
 * (`CatalogRepository`/`ReadingProgressRepository`/`DownloadRepository`), and for resolving a
 * tapped book into something playable, reuses [AppContainer.openReader] wholesale rather than
 * re-deriving auth headers/acquisition selection — the exact same resolution path Book Detail's
 * "Play" button and Home's "Continue listening" shelf already use, so CarPlay gets the same
 * `bookActions.noteOpened` recency tracking for free too.
 *
 * Deliberately simpler than Android Auto's tree in two ways, not oversights:
 * - No per-server folder split — CarPlay's own list-length conventions favor fewer, flatter
 *   lists more than Android Auto's does; "All audiobooks" merges every server into one list.
 * - No auto-paging past non-audio pages (Android Auto's `MAX_SOURCE_PAGES` loop, needed because
 *   some servers don't filter by format server-side) — a single generously-sized page is
 *   enough for CarPlay's stricter per-list item conventions; a server whose first page happens
 *   to have zero audiobooks yields fewer results here than Android Auto's list would, a real,
 *   accepted v1 gap, not silently wrong.
 * - "Downloaded" only reflects what's downloaded, for browsing — resolving a tap always goes
 *   through the network/stream path (via [AppContainer.openReader]), not the local file, since
 *   `AudiobookPlaybackController` has no local-file playback path built/tested yet. A real,
 *   accepted v1 gap: tapping a downloaded book on CarPlay still needs a network connection.
 */
class CarPlayLibraryBridge(
    private val appContainer: AppContainer,
    private val scope: CoroutineScope,
) {
    private val catalogRepository get() = appContainer.catalogRepository
    private val progressRepository get() = appContainer.progressRepository
    private val downloadRepository get() = appContainer.downloadRepository

    fun continueListening(onResult: (List<CarPlayAudiobookCard>) -> Unit) {
        scope.launch {
            val cards = progressRepository.observeInProgress().first()
                .filter { it.format == ContentFormat.AUDIOBOOK && !it.title.isNullOrBlank() }
                .map { it.toCard() }
            withContext(Dispatchers.Main) { onResult(cards) }
        }
    }

    fun downloaded(onResult: (List<CarPlayAudiobookCard>) -> Unit) {
        scope.launch {
            val cards = downloadRepository.downloads.first()
                .filter { it.format == ContentFormat.AUDIOBOOK && it.status == DownloadStatus.DONE }
                .map { download ->
                    CarPlayAudiobookCard(
                        serverId = download.serverId,
                        bookId = download.bookId,
                        title = download.title,
                        author = download.authorLine.takeIf { it.isNotBlank() },
                        coverUrl = download.coverUrl,
                        progress = progressRepository.get(download.serverId, download.bookId)?.percent,
                    )
                }
            withContext(Dispatchers.Main) { onResult(cards) }
        }
    }

    fun allAudiobooks(onResult: (List<CarPlayAudiobookCard>) -> Unit) {
        scope.launch {
            val cards = when (
                val result = catalogRepository.allBooks(
                    query = null,
                    sort = BookSort.RECENT,
                    page = 0,
                    pageSize = MAX_AUDIOBOOKS,
                    formats = AUDIO_ONLY,
                )
            ) {
                is Outcome.Success -> result.value.books.map { book ->
                    CarPlayAudiobookCard(
                        serverId = book.primary.serverId,
                        bookId = book.primary.bookId,
                        title = book.title,
                        author = book.authorLine.takeIf { it.isNotBlank() },
                        coverUrl = book.coverUrl,
                        progress = null,
                    )
                }
                is Outcome.Failure -> emptyList()
            }
            withContext(Dispatchers.Main) { onResult(cards) }
        }
    }

    /** Null when the book can't be resolved (not found, or no audio acquisition). */
    fun resolve(serverId: String, bookId: String, onResolved: (CarPlayPlayableAudiobook?) -> Unit) {
        scope.launch {
            val detail = (catalogRepository.detail(serverId, bookId) as? Outcome.Success)?.value
            var playable: CarPlayPlayableAudiobook? = null
            if (detail != null) {
                appContainer.openReader(detail, serverId, bookId) { _, _, format, url, authHeader, _, title, audiobook ->
                    if (format == ContentFormat.AUDIOBOOK && audiobook != null) {
                        playable = CarPlayPlayableAudiobook(
                            serverId = serverId,
                            bookId = bookId,
                            title = title,
                            author = audiobook.author,
                            narrator = audiobook.narrator,
                            coverUrl = audiobook.coverUrl,
                            durationMs = audiobook.durationMs,
                            chapters = audiobook.chapters,
                            digestUrl = url,
                            authHeader = authHeader,
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) { onResolved(playable) }
        }
    }

    private fun ReadingProgress.toCard() = CarPlayAudiobookCard(
        serverId = serverId,
        bookId = bookId,
        title = title.orEmpty(),
        author = author,
        coverUrl = coverUrl,
        progress = percent,
    )

    private companion object {
        // Smaller than Android Auto's MAX_AUDIOBOOKS=200 — CarPlay's own HIG favors shorter
        // lists than Android Auto's browse tree does.
        const val MAX_AUDIOBOOKS = 100
        val AUDIO_ONLY = setOf(ContentFormat.AUDIOBOOK)
    }
}

data class CarPlayAudiobookCard(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    /** 0.0-1.0 listening progress, when known. */
    val progress: Double?,
)

data class CarPlayPlayableAudiobook(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val chapters: List<Chapter>,
    /** Stream URL — see this file's own doc comment on why a downloaded copy still streams. */
    val digestUrl: String,
    val authHeader: String?,
)
