package net.dexxicon.reader.feature.catalog.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.feature.catalog.BookDetailScreen
import net.dexxicon.reader.feature.catalog.CatalogScreen

@Serializable
data class CatalogRoute(val serverId: String, val serverName: String)

/**
 * @param serverId / bookId the copy whose metadata to load.
 * @param copies every server carrying this exact book, encoded `"s1:b1|s2:b2"`. Empty when
 *   the book was opened from a single-server context (per-server catalog, Library).
 */
@Serializable
data class BookDetailRoute(
    val serverId: String,
    val bookId: String,
    val copies: String = "",
)

fun NavController.navigateToCatalog(serverId: String, serverName: String) =
    navigate(CatalogRoute(serverId, serverName))

fun NavController.navigateToBookDetail(serverId: String, bookId: String) =
    navigate(BookDetailRoute(serverId, bookId))

fun NavController.navigateToBookDetail(book: AggregatedBook) =
    navigate(
        BookDetailRoute(
            serverId = book.primary.serverId,
            bookId = book.primary.bookId,
            copies = book.copies.joinToString("|") { "${it.serverId}:${it.bookId}" },
        ),
    )

fun NavGraphBuilder.catalogSection(
    navController: NavController,
    onOpenReader: (serverId: String, bookId: String, format: ContentFormat) -> Unit = { _, _, _ -> },
) {
    composable<CatalogRoute> {
        CatalogScreen(
            onBack = { navController.popBackStack() },
            onOpenBook = { serverId, bookId ->
                navController.navigate(BookDetailRoute(serverId, bookId))
            },
        )
    }
    composable<BookDetailRoute> {
        BookDetailScreen(
            onBack = { navController.popBackStack() },
            onRead = onOpenReader,
        )
    }
}
