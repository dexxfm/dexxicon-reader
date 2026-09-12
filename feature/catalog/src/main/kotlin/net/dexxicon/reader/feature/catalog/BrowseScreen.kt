package net.dexxicon.reader.feature.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.shared.library.LibraryContent
import net.dexxicon.reader.shared.library.LibraryState

/**
 * Phase 4 Stage D (issue #133) — a thin platform entry point now: [LibraryState] +
 * [LibraryContent] (`:shared`) hold the real logic and render, identical to `:shared`'s own
 * `library/LibraryScreen.kt` wrapper. No Hilt, no `hiltViewModel()`, for this screen at all —
 * `BrowseViewModel` is gone; [AndroidAppContainer] supplies the same process-lifetime,
 * non-Hilt `AppContainer` `:shared` itself is built from (same pattern Book Detail's
 * `BookDetailScreen.kt` and Home's `LibraryScreen.kt` established, issues #126/#130).
 *
 * [onOpenReader]'s simple `(serverId, bookId, format) -> Unit` shape (native's own reader
 * screens already resolve everything else independently via their own Hilt-injected repos)
 * is a strict subset of [net.dexxicon.reader.shared.OnOpenReader]'s richer one — the adapter
 * below just discards the fields native doesn't need, same as every other thin wrapper's.
 */
@Composable
fun BrowseScreen(
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: (serverId: String, bookId: String, format: ContentFormat) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { AndroidAppContainer.get(context) }
    val state = remember { LibraryState(container, scope) }
    LibraryContent(
        state = state,
        onOpenBook = onOpenBook,
        onOpenReader = { sid, bid, format, _, _, _, _ -> onOpenReader(sid, bid, format) },
    )
}
