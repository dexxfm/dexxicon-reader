package net.dexxicon.reader.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import net.dexxicon.reader.feature.catalog.navigation.catalogSection
import net.dexxicon.reader.feature.catalog.navigation.navigateToBookDetail
import net.dexxicon.reader.feature.catalog.navigation.navigateToCatalog
import net.dexxicon.reader.feature.library.LibraryScreen
import net.dexxicon.reader.feature.reader.epub.navigation.epubReaderSection
import net.dexxicon.reader.feature.reader.epub.navigation.navigateToEpubReader
import net.dexxicon.reader.feature.servers.ServersScreen
import net.dexxicon.reader.feature.servers.navigation.navigateToAddServer
import net.dexxicon.reader.feature.servers.navigation.navigateToEditServer
import net.dexxicon.reader.feature.servers.navigation.serversSection
import net.dexxicon.reader.ui.PlaceholderScreen

@Composable
fun DexxiconNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelRoute.Library,
        modifier = modifier,
    ) {
        composable<TopLevelRoute.Library> {
            LibraryScreen(
                onOpenBook = { serverId, bookId ->
                    navController.navigateToBookDetail(serverId, bookId)
                },
            )
        }

        composable<TopLevelRoute.Browse> {
            ServersScreen(
                showBack = false,
                onBack = {},
                onAddServer = { navController.navigateToAddServer() },
                onEditServer = { navController.navigateToEditServer(it) },
                onOpenServer = { id, name -> navController.navigateToCatalog(id, name) },
            )
        }

        composable<TopLevelRoute.Settings> {
            PlaceholderScreen(
                title = "Settings",
                body = "Theme, downloads, sync providers and about.",
            )
        }

        serversSection(
            onBack = { navController.popBackStack() },
            navController = navController,
        )

        catalogSection(
            navController = navController,
            onOpenReader = { serverId, bookId ->
                navController.navigateToEpubReader(serverId, bookId)
            },
        )

        epubReaderSection(navController)
    }
}
