package net.dexxicon.reader.shared.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Phase 4 Stage D (issue #133) — thin platform entry point: [LibraryState] + [LibraryContent]
 * hold the real logic and render, shared with native's identical wrapper
 * (`feature/catalog/BrowseScreen.kt`). Constructed once per screen instance — no per-item key,
 * same as [net.dexxicon.reader.shared.home.HomeScreen].
 */
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: OnOpenReader,
) {
    val scope = rememberCoroutineScope()
    val state = remember { LibraryState(container, scope) }
    LibraryContent(state = state, onOpenBook = onOpenBook, onOpenReader = onOpenReader)
}
