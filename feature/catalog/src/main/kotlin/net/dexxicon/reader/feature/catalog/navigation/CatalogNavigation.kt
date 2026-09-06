package net.dexxicon.reader.feature.catalog.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import androidx.navigation.toRoute
import net.dexxicon.reader.feature.catalog.BookDetailScreen
import net.dexxicon.reader.feature.catalog.CatalogScreen

@Serializable
data class CatalogRoute(val serverId: String, val serverName: String)

@Serializable
data class BookDetailRoute(val serverId: String, val bookId: String)

fun NavController.navigateToCatalog(serverId: String, serverName: String) =
    navigate(CatalogRoute(serverId, serverName))

fun NavGraphBuilder.catalogSection(
    navController: NavController,
    onOpenReader: (serverId: String, bookId: String) -> Unit = { _, _ -> },
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
