package net.dexxicon.reader.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
            PlaceholderScreen(
                title = "Library",
                body = "Downloaded and in-progress books, comics and audiobooks will live here.",
            )
        }

        composable<TopLevelRoute.Browse> {
            ServersScreen(
                showBack = false,
                onBack = {},
                onAddServer = { navController.navigateToAddServer() },
                onEditServer = { navController.navigateToEditServer(it) },
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
    }
}
