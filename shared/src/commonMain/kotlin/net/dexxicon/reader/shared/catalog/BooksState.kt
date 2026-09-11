package net.dexxicon.reader.shared.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf

/**
 * [BooksScreen]'s state + behaviour (issue #80) — shelf filter, search, sort, and page-at-a-
 * time pagination on top of [CatalogRepository], which already supports all of this. Same
 * plain-class-holding-a-`CoroutineScope` shape as [net.dexxicon.reader.shared.servers.AddServerState]
 * (no `ViewModel`/Hilt here, see [net.dexxicon.reader.shared.di.AppContainer]'s doc comment).
 *
 * Pagination is an explicit "Load more" action rather than scroll-position auto-loading —
 * simpler, and one less thing to get subtly wrong on a platform (iOS) nothing here can be
 * tested against locally.
 */
class BooksState(
    private val catalogRepository: CatalogRepository,
    private val serverId: String,
    private val scope: CoroutineScope,
) {
    var shelves: List<CatalogShelf> by mutableStateOf(emptyList())
        private set
    var selectedShelfId: String? by mutableStateOf(null)
        private set

    /** What's currently typed, before [search] commits it as [activeQuery]. */
    var queryDraft: String by mutableStateOf("")
    private var activeQuery: String? by mutableStateOf(null)

    var sort: BookSort by mutableStateOf(BookSort.RECENT)
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

    private var page = 0
    /** Guards against a slow first page landing after a filter change has already started a
     * newer one — only the request matching this token is allowed to apply its result. */
    private var requestToken = 0

    init {
        scope.launch {
            when (val result = catalogRepository.shelves(serverId)) {
                is Outcome.Success -> shelves = result.value
                is Outcome.Failure -> Unit // Shelves are a nice-to-have filter; the book list's own error covers a truly unreachable server.
            }
        }
        refresh()
    }

    fun onShelfSelected(shelfId: String?) {
        if (shelfId == selectedShelfId) return
        selectedShelfId = shelfId
        refresh()
    }

    fun onSortChange(newSort: BookSort) {
        if (newSort == sort) return
        sort = newSort
        refresh()
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

    private fun refresh() {
        val token = ++requestToken
        page = 0
        loading = true
        error = null
        scope.launch {
            val result = catalogRepository.books(serverId, selectedShelfId, activeQuery, sort, page = 0)
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
            val result = catalogRepository.books(serverId, selectedShelfId, activeQuery, sort, page = nextPage)
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
