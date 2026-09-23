package net.dexxicon.reader.shared.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.sync.ServerSyncFailure
import net.dexxicon.reader.core.data.sync.SyncReport
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.HomeLayout
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader
import net.dexxicon.reader.shared.toBookDetail

data class ContinueItem(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val format: ContentFormat,
    val percent: Float,
    /** issue #257 — cover's series-number badge. */
    val seriesIndex: Double? = null,
    /** issue #258 — this book's offline copy, if any, so the card can show the same
     * "downloaded" badge and download/remove menu state every other shelf does. */
    val downloadStatus: DownloadStatus? = null,
)

/** A book the user flagged "Want to read" on a server — shown in the On Deck shelf. */
data class OnDeckItem(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val format: ContentFormat,
    /** issue #257 — cover's series-number badge. */
    val seriesIndex: Double? = null,
    /** issue #258 — see [ContinueItem.downloadStatus]. */
    val downloadStatus: DownloadStatus? = null,
)

data class HomeUiState(
    val onDeck: List<OnDeckItem> = emptyList(),
    val continueReading: List<ContinueItem> = emptyList(),
    val continueListening: List<ContinueItem> = emptyList(),
    val downloads: List<Download> = emptyList(),
    /** Reading progress (0–1) for the Downloaded grid, keyed by "serverId::bookId". */
    val downloadProgress: Map<String, Float> = emptyMap(),
    /** issue #257 — series position for the Downloaded grid's covers, keyed the same way.
     *  Taken from the progress row's cached snapshot; downloads don't store it themselves. */
    val downloadSeriesIndex: Map<String, Double> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** When the last sync pass ran; null before the first one completes. */
    val lastSyncedAt: Long? = null,
    /** Servers that didn't reconcile on the last pass. Empty = everything's current. */
    val syncFailures: List<ServerSyncFailure> = emptyList(),
)

/**
 * Phase 4 Stage C (issue #130) — the one Home state class, replacing native's Hilt
 * `LibraryViewModel` (native's `feature/library` module — the route class is named
 * `TopLevelRoute.Library` for historical reasons, but it's the screen titled "Home": Continue
 * reading/listening, On Deck, Downloaded). Built from the whole [AppContainer], the same shape
 * [net.dexxicon.reader.shared.catalog.BookDetailState] already uses — every dependency this
 * class needs (`downloadRepository`, `progressRepository`, `catalogRepository`, `bookActions`)
 * was already commonMain and already exposed on [AppContainer] before this class existed, from
 * issue #126's Book Detail unification; no new container wiring was needed for this screen.
 *
 * The `combine(...).stateIn(...)` shape and every shelf-building rule below are ported as-is
 * from `LibraryViewModel`: in-progress rows sorted by recency, split into
 * [HomeUiState.continueReading]/[HomeUiState.continueListening] by format, On Deck de-duped
 * against whatever's already in progress ("a book that's been started isn't 'on deck' any
 * more, whatever the server says").
 */
class HomeState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val refreshing = MutableStateFlow(false)
    private val onDeck = MutableStateFlow<List<OnDeckItem>>(emptyList())
    private val lastReport = MutableStateFlow<SyncReport?>(null)

    val uiState: StateFlow<HomeUiState> =
        combine(
            container.downloadRepository.downloads,
            container.progressRepository.observeAll(),
            onDeck,
            refreshing,
            lastReport,
        ) { downloads, progressByKey, onDeckItems, isRefreshing, report ->
            val inProgress = progressByKey.values
                .filter { it.isInProgress }
                .sortedByDescending { it.updatedAt }
            // issue #258 — Continue/On Deck cards never learned about downloads at all, so a
            // downloaded book showed no badge there and its long-press menu offered to download
            // it again instead of removing it.
            val downloadStatusByKey = downloads.associate { it.key to it.status }
            val items = inProgress.mapNotNull { it.toContinueItem(downloadStatusByKey[it.key]) }
            val inProgressKeys = inProgress.map { "${it.serverId}::${it.bookId}" }.toSet()
            HomeUiState(
                // A book that's been started isn't "on deck" any more, whatever the server says.
                onDeck = onDeckItems
                    .filter { "${it.serverId}::${it.bookId}" !in inProgressKeys }
                    .map { it.copy(downloadStatus = downloadStatusByKey["${it.serverId}::${it.bookId}"]) },
                continueReading = items.filter { it.format != ContentFormat.AUDIOBOOK },
                continueListening = items.filter { it.format == ContentFormat.AUDIOBOOK },
                downloads = downloads,
                downloadProgress = progressByKey
                    .mapValues { (_, p) -> (p.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }
                    .filterValues { it > 0f },
                downloadSeriesIndex = progressByKey
                    .mapNotNull { (key, p) -> p.seriesIndex?.let { key to it } }
                    .toMap(),
                loading = false,
                refreshing = isRefreshing,
                lastSyncedAt = report?.at,
                syncFailures = report?.failures.orEmpty(),
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** The user's shelf order and hidden shelves (issue #255), from Settings › Home screen. */
    val layout: StateFlow<HomeLayout> = container.appPreferences.preferences
        .map { it.homeLayout }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeLayout())

    /** Whether the offline/download shelf should show at all — same platform-capability
     * check [net.dexxicon.reader.shared.catalog.BookDetailState.supportsDownloads] uses. */
    val supportsDownloads: Boolean get() = container.downloadRepository.supportsDownloads

    init {
        // Reconcile progress with KOReader / other devices (both directions).
        refresh()
    }

    fun refresh() {
        loadOnDeck()
        if (refreshing.value) return
        scope.launch {
            refreshing.value = true
            runCatching { container.progressRepository.syncProgress() }.getOrNull()?.let { lastReport.value = it }
            refreshing.value = false
        }
    }

    private fun loadOnDeck() {
        scope.launch {
            when (val result = container.catalogRepository.onDeck()) {
                is Outcome.Success -> onDeck.value = result.value.map { it.toOnDeckItem() }
                is Outcome.Failure -> Unit // keep whatever's already there
            }
        }
    }

    private fun BookSummary.toOnDeckItem() = OnDeckItem(
        serverId = serverId,
        bookId = id,
        title = title,
        author = authorLine.takeIf { it.isNotBlank() },
        coverUrl = coverUrl,
        format = format,
        seriesIndex = seriesIndex,
    )

    fun markRead(serverId: String, bookId: String) = container.bookActions.markFinished(serverId, bookId, true)
    fun markUnread(serverId: String, bookId: String) = container.bookActions.markFinished(serverId, bookId, false)
    fun setReadingStatus(serverId: String, bookId: String, status: ReadingStatus) =
        container.bookActions.setReadingStatus(serverId, bookId, status)
    fun downloadOrRemove(serverId: String, bookId: String, status: DownloadStatus?) =
        container.bookActions.downloadOrRemove(serverId, bookId, status)

    fun remove(download: Download) {
        scope.launch { container.downloadRepository.remove(download.serverId, download.bookId) }
    }

    /** A tap on a Continue reading/listening card — unlike On Deck (which just opens Book
     * Detail), this needs the full [net.dexxicon.reader.core.model.BookDetail] to resolve a
     * reader launch (a [ContinueItem] only carries the lightweight metadata the shelf itself
     * needs), so it fetches first, then hands off through the same
     * [net.dexxicon.reader.shared.openReader] helper Book Detail's "Read"/"Play" button uses.
     *
     * issue #248: that network fetch used to gate the whole tap — offline, it failed and this
     * silently returned before [onOpenReader] ever fired, so a downloaded book's own card did
     * nothing at all (no error, unlike #246/#247's now-fixed in-reader failure, since the
     * reader screen never even opened). Android's reader Activities ignore every argument here
     * except [ContinueItem.serverId]/[ContinueItem.bookId]/[ContinueItem.format] — see
     * `MainActivity`'s `onOpenReader` wiring — and resolve everything else themselves,
     * including a local download (issue #246/#247), so it's safe to still call [onOpenReader]
     * with [item]'s own cached metadata once a local copy is confirmed. issue #250: iOS's
     * native readers *do* use the passed url/authHeader directly (no independent local-file
     * check of their own) — the offline branch below reconstructs a real
     * [net.dexxicon.reader.core.model.BookDetail] via
     * [toBookDetail] and routes it through the same [container.openReader] every other call
     * site uses, rather than hand-building a partial [OnOpenReader] call, so it picks up
     * [net.dexxicon.reader.core.model.Download.durationMs] the same way Book Detail's own
     * Play button does — an earlier version of this fix called [onOpenReader] directly with
     * a hardcoded null `audiobook`, which quietly left a downloaded audiobook's Continue
     * Listening card stuck showing 0:00/0:00 even though it played correctly from Book
     * Detail. */
    fun continueReading(item: ContinueItem, onOpenReader: OnOpenReader) {
        scope.launch {
            val detail = (container.catalogRepository.detail(item.serverId, item.bookId) as? Outcome.Success)
                ?.value
                ?: container.downloadRepository.get(item.serverId, item.bookId)
                    ?.takeIf { it.status == DownloadStatus.DONE }
                    ?.toBookDetail()
                ?: return@launch
            container.openReader(detail, item.serverId, item.bookId, onOpenReader)
        }
    }

    private fun ReadingProgress.toContinueItem(downloadStatus: DownloadStatus?): ContinueItem? {
        val fmt = format ?: return null
        val name = title?.takeIf { it.isNotBlank() } ?: return null
        return ContinueItem(
            serverId = serverId,
            bookId = bookId,
            title = name,
            author = author,
            coverUrl = coverUrl,
            format = fmt,
            percent = (percent ?: 0.0).toFloat().coerceIn(0f, 1f),
            seriesIndex = seriesIndex,
            downloadStatus = downloadStatus,
        )
    }
}
