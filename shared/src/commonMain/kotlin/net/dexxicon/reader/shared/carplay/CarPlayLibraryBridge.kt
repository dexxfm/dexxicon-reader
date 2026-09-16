package net.dexxicon.reader.shared.carplay

import io.ktor.http.Url
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
 * - "Downloaded" only reflects what's downloaded, for browsing — resolving a tap always goes
 *   through the network/stream path (via [AppContainer.openReader]), not the local file, since
 *   `AudiobookPlaybackController` has no local-file playback path built/tested yet. A real,
 *   accepted v1 gap: tapping a downloaded book on CarPlay still needs a network connection.
 *
 * [allAudiobooks] *does* mirror Android Auto's `MAX_SOURCE_PAGES` auto-paging (found live,
 * not anticipated up front: a single unfiltered page of "recent across all formats" genuinely
 * returned zero audiobooks against a real test library, not just "fewer" as first assumed —
 * `CatalogRepository.allBooks`'s `formats` filter is applied client-side to whatever that page
 * already contains, same reason Android Auto's own implementation needs the same loop).
 *
 * Every [CarPlayAudiobookCard] also carries a resolved [CarPlayAudiobookCard.coverAuthHeader] —
 * found live too: cover images silently failed to load for every single book, since Swift's
 * plain `URLSession.shared.dataTask` had no `Authorization` header at all, and these servers
 * require one even for cover art. Resolved here the same way [openReader] resolves it for an
 * acquisition URL, via [AppContainer.authHeaderProvider].
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
                        coverAuthHeader = coverAuthHeaderFor(download.coverUrl),
                        progress = progressRepository.get(download.serverId, download.bookId)?.percent,
                    )
                }
            withContext(Dispatchers.Main) { onResult(cards) }
        }
    }

    /**
     * issue #240, found live: a single unfiltered page genuinely returned zero audiobooks
     * against a real test library — `allBooks`'s `formats` filter is applied client-side to
     * whatever page N of "recent across all formats" already contains (see
     * [net.dexxicon.reader.core.data.CatalogRepository.allBooks]), so a server whose first
     * page of *anything recent* has no audiobooks yields nothing at all, not just "fewer".
     * This mirrors Android Auto's own `MediaLibraryContentSourceImpl.audiobooks()` fix for the
     * identical problem: keep asking for more pages until enough audiobooks are collected.
     */
    fun allAudiobooks(onResult: (List<CarPlayAudiobookCard>) -> Unit) {
        scope.launch {
            val collected = mutableListOf<CarPlayAudiobookCard>()
            var sourcePage = 0
            var hasMore = true
            while (hasMore && sourcePage < MAX_SOURCE_PAGES && collected.size < MAX_AUDIOBOOKS) {
                when (
                    val result = catalogRepository.allBooks(
                        query = null,
                        sort = BookSort.RECENT,
                        page = sourcePage,
                        pageSize = SOURCE_PAGE_SIZE,
                        formats = AUDIO_ONLY,
                    )
                ) {
                    is Outcome.Success -> {
                        collected += result.value.books.map { book ->
                            CarPlayAudiobookCard(
                                serverId = book.primary.serverId,
                                bookId = book.primary.bookId,
                                title = book.title,
                                author = book.authorLine.takeIf { it.isNotBlank() },
                                coverUrl = book.coverUrl,
                                coverAuthHeader = coverAuthHeaderFor(book.coverUrl),
                                progress = null,
                            )
                        }
                        hasMore = result.value.hasMore
                    }
                    is Outcome.Failure -> hasMore = false
                }
                sourcePage++
            }
            val cards = collected.distinctBy { "${it.serverId}::${it.bookId}" }.take(MAX_AUDIOBOOKS)
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
        coverAuthHeader = coverAuthHeaderFor(coverUrl),
        progress = percent,
    )

    private fun coverAuthHeaderFor(coverUrl: String?): String? =
        coverUrl?.let { runCatching { appContainer.authHeaderProvider.authHeader(Url(it)) }.getOrNull() }

    private companion object {
        // Smaller than Android Auto's MAX_AUDIOBOOKS=200 — CarPlay's own HIG favors shorter
        // lists than Android Auto's browse tree does. SOURCE_PAGE_SIZE/MAX_SOURCE_PAGES match
        // Android Auto's own MediaLibraryContentSourceImpl values exactly.
        const val MAX_AUDIOBOOKS = 100
        const val SOURCE_PAGE_SIZE = 60
        const val MAX_SOURCE_PAGES = 10
        val AUDIO_ONLY = setOf(ContentFormat.AUDIOBOOK)
    }
}

data class CarPlayAudiobookCard(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    /** Resolved once here (see this file's own doc comment) — the server requires this for
     *  cover art, same as any other acquisition URL, so Swift's cover-fetch needs it attached
     *  as an `Authorization` header the same way `AudiobookPlaybackController` already does. */
    val coverAuthHeader: String?,
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
