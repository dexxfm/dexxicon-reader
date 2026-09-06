package net.dexxicon.reader.feature.reader.comic.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.reader.comic.ComicReaderScreen

@Serializable
data class ComicReaderRoute(val serverId: String, val bookId: String)

fun NavController.navigateToComicReader(serverId: String, bookId: String) =
    navigate(ComicReaderRoute(serverId, bookId))

fun NavGraphBuilder.comicReaderSection(navController: NavController) {
    composable<ComicReaderRoute> {
        ComicReaderScreen(onBack = { navController.popBackStack() })
    }
}
