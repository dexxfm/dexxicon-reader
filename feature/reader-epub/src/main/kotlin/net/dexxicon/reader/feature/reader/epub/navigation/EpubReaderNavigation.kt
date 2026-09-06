package net.dexxicon.reader.feature.reader.epub.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.reader.epub.EpubReaderScreen

@Serializable
data class EpubReaderRoute(val serverId: String, val bookId: String)

fun NavController.navigateToEpubReader(serverId: String, bookId: String) =
    navigate(EpubReaderRoute(serverId, bookId))

fun NavGraphBuilder.epubReaderSection(navController: NavController) {
    composable<EpubReaderRoute> {
        EpubReaderScreen(onBack = { navController.popBackStack() })
    }
}
