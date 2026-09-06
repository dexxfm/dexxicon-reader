package net.dexxicon.reader.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
                body = "Your downloaded and in-progress books, comics and audiobooks will live here.",
            )
        }
        composable<TopLevelRoute.Browse> {
            PlaceholderScreen(
                title = "Browse",
                body = "Add a server, then browse its OPDS catalog. Servers, feeds and search land in the next phase.",
            )
        }
        composable<TopLevelRoute.Settings> {
            PlaceholderScreen(
                title = "Settings",
                body = "Theme, downloads, sync providers and about.",
            )
        }
    }
}
