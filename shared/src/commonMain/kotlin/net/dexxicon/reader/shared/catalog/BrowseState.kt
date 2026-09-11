package net.dexxicon.reader.shared.catalog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary

/**
 * [BrowseScreen]'s state — the merged, de-duplicated view across every configured server
 * (issue #82), on [CatalogRepository.allBooks]/[CatalogRepository.onDeck] (both already
 * existed, unused until now). No shelf filter here (shelves are a per-server concept — see
 * [BooksState], the single-server screen this deliberately doesn't duplicate logic with
 * beyond the shared shape) and no pagination — [CatalogRepository.allBooks] pages once per
 * server internally and merges, so "page" here would mean re-merging every server's next
 * page together, a real feature this MVP intentionally leaves out.
 */
class BrowseState(
    private val catalogRepository: CatalogRepository,
    private val scope: CoroutineScope,
) {
    var onDeck: List<BookSummary> by mutableStateOf(emptyList())
        private set

    var queryDraft: String by mutableStateOf("")
    private var activeQuery: String? by mutableStateOf(null)

    var sort: BookSort by mutableStateOf(BookSort.RECENT)
        private set

    var books: List<AggregatedBook> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var requestToken = 0

    init {
        scope.launch {
            when (val result = catalogRepository.onDeck()) {
                is Outcome.Success -> onDeck = result.value
                is Outcome.Failure -> Unit // Best-effort shelf; the main grid's own error covers a truly unreachable state.
            }
        }
        refresh()
    }

    fun onSortChange(newSort: BookSort) {
        if (newSort == sort) return
        sort = newSort
        refresh()
    }

    fun search() {
        val trimmed = queryDraft.trim().takeIf { it.isNotBlank() }
        if (trimmed == activeQuery) return
        activeQuery = trimmed
        refresh()
    }

    fun retry() = refresh()

    private fun refresh() {
        val token = ++requestToken
        loading = true
        error = null
        scope.launch {
            val result = catalogRepository.allBooks(activeQuery, sort, page = 0)
            if (token != requestToken) return@launch
            loading = false
            when (result) {
                is Outcome.Success -> books = result.value.books
                is Outcome.Failure -> {
                    books = emptyList()
                    error = result.error.message ?: "Couldn't load your library"
                }
            }
        }
    }
}
