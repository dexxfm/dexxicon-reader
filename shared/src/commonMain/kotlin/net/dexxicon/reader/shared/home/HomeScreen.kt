package net.dexxicon.reader.shared.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Phase 4 Stage C (issue #130) — thin platform entry point: [HomeState] + [HomeContent] hold
 * the real logic and render, shared with native's identical wrapper
 * (`feature/library/LibraryScreen.kt`). Constructed once per screen instance — unlike
 * [net.dexxicon.reader.shared.catalog.BookDetailScreen], there's no per-item key to `remember`
 * against.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onOpenReader: OnOpenReader,
) {
    val scope = rememberCoroutineScope()
    val state = remember { HomeState(container, scope) }
    HomeContent(state = state, onOpenBook = onOpenBook, onOpenReader = onOpenReader)
}
