package net.dexxicon.reader.shared.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.library.BookOverlays
import net.dexxicon.reader.shared.openReader
import net.dexxicon.reader.shared.toBookDetail

/**
 * [BooksScreen]'s state + behaviour (issue #80, extended in Stage E1 follow-up per the user's
 * "Server Browser should have the same options as Library" request) — search, sort,
 * content-format filter, grid/list toggle, page-at-a-time pagination, progress/download
 * overlays, and the same mark-read/status/download context-menu actions
 * [net.dexxicon.reader.shared.library.LibraryState] exposes. Deliberately its *own* class
 * rather than sharing `LibraryState`: this screen is scoped to one [serverId] (not a merged,
 * de-duplicated cross-server view), so the two operate on genuinely different
 * [net.dexxicon.reader.core.data.CatalogRepository] methods (`books` vs `allBooks`) and result
 * types (plain [BookSummary] vs `AggregatedBook`). Same plain-class-holding-a-`CoroutineScope`
 * shape as [net.dexxicon.reader.shared.servers.AddServerState].
 *
 * Pagination is an explicit "Load more" action rather than scroll-position auto-loading —
 * simpler, and one less thing to get subtly wrong on a platform (iOS) nothing here can be
 * tested against locally.
 */
class BooksState(
    private val container: AppContainer,
    private val serverId: String,
    private val scope: CoroutineScope,
) {
    /** The screen's title — mirrors native's `CatalogScreen` showing the real server name
     * rather than a generic label. */
    val serverName: StateFlow<String> =
        container.serverRepository.server(serverId)
            .map { it?.displayName ?: "" }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), "")

    /** issue #298 — this server is an OPDS catalog, so its books get only a catalog's actions. */
    val isCatalog: StateFlow<Boolean> =
        container.serverRepository.server(serverId)
            .map { it != null && !it.type.supportsNativeApi }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    /** What's currently typed, before [search] commits it as [activeQuery]. */
    var queryDraft: String by mutableStateOf("")
    private var activeQuery: String? by mutableStateOf(null)

    var sort: BookSort by mutableStateOf(BookSort.RECENT)
        private set
    var filter: ContentFilter by mutableStateOf(ContentFilter.ALL)
        private set
    var viewMode: BookViewMode by mutableStateOf(BookViewMode.GRID)
        private set
    var coverTapAction: CoverTapAction by mutableStateOf(CoverTapAction.OPEN_DETAILS)
        private set

    var books: List<BookSummary> by mutableStateOf(emptyList())
        private set
    var hasMore: Boolean by mutableStateOf(false)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var loadingMore: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Per-book overlays keyed by "serverId::bookId" — same shape as Library's, just always
     * matching against this one [serverId]. */
    val overlays: StateFlow<BookOverlays> =
        combine(
            container.progressRepository.observeAll(),
            container.downloadRepository.downloads,
        ) { progress, downloads ->
            BookOverlays(
                progress = progress
                    .mapValues { (_, p) -> (p.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }
                    .filterValues { it > 0f },
                downloaded = downloads
                    .filter { it.status == DownloadStatus.DONE }
                    .map { "${it.serverId}::${it.bookId}" }
                    .toSet(),
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), BookOverlays())

    private var page = 0
    /** Guards against a slow first page landing after a filter change has already started a
     * newer one — only the request matching this token is allowed to apply its result. */
    private var requestToken = 0

    init {
        scope.launch {
            container.appPreferences.preferences.map { it.catalogView }.distinctUntilChanged().collect {
                viewMode = it
            }
        }
        scope.launch {
            container.appPreferences.preferences.map { it.catalogSort }.distinctUntilChanged().collect {
                if (it == sort) return@collect
                sort = it
                refresh()
            }
        }
        scope.launch {
            container.appPreferences.preferences.map { it.coverTapAction }.distinctUntilChanged().collect {
                coverTapAction = it
            }
        }
        refresh()
    }

    /** Persists this screen's own sort choice — the Settings default is untouched. The
     *  actual [sort] update and [refresh] happen when that write is reflected back through
     *  the preference collector above, same as [toggleViewMode]. */
    fun onSortChange(newSort: BookSort) {
        if (newSort == sort) return
        scope.launch { container.appPreferences.setCatalogSort(newSort) }
    }

    fun onFilterSelected(newFilter: ContentFilter) {
        if (newFilter == filter) return
        filter = newFilter
        refresh()
    }

    /** Flips this screen's own layout — the Settings default is untouched. */
    fun toggleViewMode() {
        val next = if (viewMode == BookViewMode.LIST) BookViewMode.GRID else BookViewMode.LIST
        scope.launch { container.appPreferences.setCatalogView(next) }
    }

    /** Commits [queryDraft] as the active search and reloads. Explicit, not per-keystroke —
     * every server round-trip here is a real network call. */
    fun search() {
        val trimmed = queryDraft.trim().takeIf { it.isNotBlank() }
        if (trimmed == activeQuery) return
        activeQuery = trimmed
        refresh()
    }

    fun clearSearch() {
        queryDraft = ""
        if (activeQuery == null) return
        activeQuery = null
        refresh()
    }

    fun retry() = refresh()

    fun markRead(bookId: String) = container.bookActions.markFinished(serverId, bookId, true)
    fun markUnread(bookId: String) = container.bookActions.markFinished(serverId, bookId, false)
    fun setReadingStatus(bookId: String, status: ReadingStatus) =
        container.bookActions.setReadingStatus(serverId, bookId, status)
    fun downloadOrRemove(bookId: String, status: DownloadStatus?) =
        container.bookActions.downloadOrRemove(serverId, bookId, status)

    /** A cover tap when [coverTapAction] is `OPEN_BOOK` — fetches the full detail, then hands
     * off through the same [net.dexxicon.reader.shared.openReader] helper every other
     * tap-to-read site uses.
     *
     * issue #248: don't gate on that (network) fetch succeeding — offline, it used to fail
     * and this silently returned before [onOpenReader] ever fired, same bug as Home's
     * Continue reading/listening cards had. issue #250 follow-up: the offline branch
     * reconstructs a real `BookDetail` via [net.dexxicon.reader.shared.toBookDetail] and
     * routes it through [container.openReader] like every other call site, rather than
     * hand-building a partial [OnOpenReader] call with a hardcoded null `audiobook` — see
     * [net.dexxicon.reader.shared.home.HomeState.continueReading]'s doc comment for why that
     * silently broke a downloaded audiobook's duration/position display. */
    fun openBook(book: BookSummary, onOpenReader: OnOpenReader) {
        scope.launch {
            val detail = (container.catalogRepository.detail(serverId, book.id) as? Outcome.Success)
                ?.value
                ?: container.downloadRepository.get(serverId, book.id)
                    ?.takeIf { it.status == DownloadStatus.DONE }
                    ?.toBookDetail()
                ?: return@launch
            container.openReader(detail, serverId, book.id, onOpenReader)
        }
    }

    private fun refresh() {
        val token = ++requestToken
        page = 0
        loading = true
        error = null
        scope.launch {
            val result = container.catalogRepository.books(
                serverId, null, activeQuery, sort, page = 0, formats = filter.formats,
            )
            if (token != requestToken) return@launch // a newer filter change already superseded this
            loading = false
            when (result) {
                is Outcome.Success -> {
                    books = result.value.books
                    hasMore = result.value.hasMore
                }
                is Outcome.Failure -> {
                    books = emptyList()
                    hasMore = false
                    error = result.error.message ?: "Couldn't load this server's catalog"
                }
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        val token = requestToken
        val nextPage = page + 1
        loadingMore = true
        scope.launch {
            val result = container.catalogRepository.books(
                serverId, null, activeQuery, sort, page = nextPage, formats = filter.formats,
            )
            if (token != requestToken) return@launch
            loadingMore = false
            when (result) {
                is Outcome.Success -> {
                    page = nextPage
                    books = books + result.value.books
                    hasMore = result.value.hasMore
                }
                is Outcome.Failure -> {
                    // Keep the books already shown; just stop offering more for this session
                    // rather than replacing a working list with an error.
                    hasMore = false
                }
            }
        }
    }
}
