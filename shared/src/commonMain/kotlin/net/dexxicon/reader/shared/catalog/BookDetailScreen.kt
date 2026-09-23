package net.dexxicon.reader.shared.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Phase 4 restructure (issue #126) — a thin platform entry point now: [BookDetailState] +
 * [BookDetailContent] hold the real logic and render, shared with native's identical wrapper
 * (`feature/catalog/BookDetailScreen.kt`). `:shared`'s own nav graph has no merged-copies
 * concept, so `copyIds` is always empty here — [BookDetailState] falls back to the single
 * `serverId`/`bookId` pair, same as native's own empty-copies fallback.
 */
@Composable
fun BookDetailScreen(
    container: AppContainer,
    serverId: String,
    bookId: String,
    onBack: () -> Unit,
    onOpenReader: OnOpenReader,
    /** issue #256 — a series by name (its series line, or the shelf's "See all"). */
    onOpenSeries: (String) -> Unit = {},
    /** issue #256 — another book from the series shelf. */
    onOpenBook: (serverId: String, bookId: String) -> Unit = { _, _ -> },
    /** issue #266 — this book's Highlights list. */
    onOpenHighlights: (title: String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state = remember(serverId, bookId) {
        BookDetailState(container, serverId, bookId, copyIds = emptyList(), scope)
    }
    BookDetailContent(
        state = state,
        onBack = onBack,
        onOpenReader = onOpenReader,
        onOpenSeries = onOpenSeries,
        onOpenBook = onOpenBook,
        onOpenHighlights = onOpenHighlights,
    )
}
