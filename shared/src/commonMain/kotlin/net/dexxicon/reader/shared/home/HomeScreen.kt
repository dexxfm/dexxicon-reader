package net.dexxicon.reader.shared.home

import androidx.compose.runtime.Composable
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.HomeSection

/**
 * Phase 4 Stage C (issue #130) — thin platform entry point: [HomeState] + [HomeContent] hold
 * the real logic and render, shared with native's identical wrapper
 * (`feature/library/LibraryScreen.kt`). Constructed once per screen instance — unlike
 * [net.dexxicon.reader.shared.catalog.BookDetailScreen], there's no per-item key to `remember`
 * against.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onOpenReader: OnOpenReader,
    onOpenSection: (HomeSection) -> Unit,
    onOpenGroup: (BookGroup) -> Unit,
) {
    HomeContent(state = state, onOpenBook = onOpenBook, onOpenReader = onOpenReader,
        onOpenSection = onOpenSection, onOpenGroup = onOpenGroup)
}
