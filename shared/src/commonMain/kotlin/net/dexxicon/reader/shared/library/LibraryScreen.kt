package net.dexxicon.reader.shared.library

import androidx.compose.runtime.Composable
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.SeriesEntry
import net.dexxicon.reader.shared.OnOpenReader

/**
 * Phase 4 Stage D (issue #133) — thin platform entry point: [LibraryState] + [LibraryContent]
 * hold the real logic and render.
 *
 * Batch B: [state] is owned by the caller (`App.kt`), not created here, so the main Library and
 * the two-pane layout's Library pane share one — a chosen tab or library then survives opening
 * a book on a wide screen, which swaps between the two.
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: OnOpenReader,
    onOpenSeries: (SeriesEntry) -> Unit,
    onOpenGroup: (BookGroup) -> Unit,
) {
    LibraryContent(
        state = state,
        onOpenBook = onOpenBook,
        onOpenReader = onOpenReader,
        onOpenSeries = onOpenSeries,
        onOpenGroup = onOpenGroup,
    )
}
