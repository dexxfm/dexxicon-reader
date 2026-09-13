package net.dexxicon.reader.feature.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.shared.catalog.BooksScreen
import net.dexxicon.reader.shared.di.AndroidAppContainer

/**
 * Phase 4 Stage G (issue #144) — a thin platform entry point now, same shape as
 * [BookDetailScreen]'s own doc comment: `:shared`'s `BooksScreen`/`BooksState` hold the real
 * logic and render. `CatalogViewModel` is gone; [AndroidAppContainer] supplies the same
 * process-lifetime, non-Hilt `AppContainer` `:shared` itself is built from.
 */
@Composable
fun CatalogScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onOpenReader: (serverId: String, bookId: String, format: ContentFormat) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val container = remember { AndroidAppContainer.get(context) }
    BooksScreen(
        container = container,
        serverId = serverId,
        onBack = onBack,
        onOpenBook = { bookId -> onOpenBook(serverId, bookId) },
        onOpenReader = { sid, bid, format, _, _, _, _ -> onOpenReader(sid, bid, format) },
    )
}
