package net.dexxicon.reader.feature.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.shared.catalog.BookDetailContent
import net.dexxicon.reader.shared.catalog.BookDetailState
import net.dexxicon.reader.shared.di.AndroidAppContainer

/**
 * Phase 4 restructure (issue #126) — a thin platform entry point now: [BookDetailState] +
 * [BookDetailContent] (`:shared`) hold the real logic and render, identical to `:shared`'s own
 * `catalog/BookDetailScreen.kt` wrapper. No Hilt, no `hiltViewModel()`, no `SavedStateHandle`
 * for this screen at all — `BookDetailViewModel` is gone; [AndroidAppContainer] supplies the
 * same process-lifetime, non-Hilt `AppContainer` `:shared` itself is built from.
 *
 * [onRead]'s simple `(serverId, bookId, format) -> Unit` shape (native's own reader screens
 * already resolve everything else — auth header, manga-genre, audiobook metadata —
 * independently via their own Hilt-injected repos) is a strict subset of
 * [net.dexxicon.reader.shared.OnOpenReader]'s richer one — the [onOpenReader] adapter below
 * just discards the fields native doesn't need rather than [BookDetailContent] needing two
 * different callback shapes.
 */
@Composable
fun BookDetailScreen(
    serverId: String,
    bookId: String,
    copies: String,
    onBack: () -> Unit,
    onRead: (serverId: String, bookId: String, format: ContentFormat) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { AndroidAppContainer.get(context) }
    val copyIds = remember(copies) {
        copies.split('|').mapNotNull { part ->
            val (sid, bid) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            sid to bid
        }
    }
    val state = remember(serverId, bookId, copyIds) {
        BookDetailState(container, serverId, bookId, copyIds, scope)
    }
    BookDetailContent(
        state = state,
        onBack = onBack,
        onOpenReader = { sid, bid, format, _, _, _, _ -> onRead(sid, bid, format) },
    )
}
