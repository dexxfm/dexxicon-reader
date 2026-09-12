package net.dexxicon.reader.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import net.dexxicon.reader.feature.catalog.BrowseScreen
import net.dexxicon.reader.feature.catalog.navigation.catalogSection
import net.dexxicon.reader.feature.catalog.navigation.navigateToBookDetail
import net.dexxicon.reader.feature.catalog.navigation.navigateToCatalog
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.feature.library.LibraryScreen
import net.dexxicon.reader.feature.reader.comic.navigation.comicReaderSection
import net.dexxicon.reader.feature.reader.comic.navigation.navigateToComicReader
import net.dexxicon.reader.feature.player.navigation.navigateToPlayer
import net.dexxicon.reader.feature.player.navigation.playerSection
import net.dexxicon.reader.feature.reader.epub.navigation.epubReaderSection
import net.dexxicon.reader.feature.reader.epub.navigation.navigateToEpubReader
import net.dexxicon.reader.feature.reader.pdf.navigation.navigateToPdfReader
import net.dexxicon.reader.feature.reader.pdf.navigation.pdfReaderSection
import net.dexxicon.reader.feature.servers.navigation.navigateToAddServer
import net.dexxicon.reader.feature.servers.navigation.navigateToEditServer
import net.dexxicon.reader.feature.servers.navigation.serversSection
import net.dexxicon.reader.feature.settings.SettingsScreen
import net.dexxicon.reader.feature.settings.navigation.bookDefaultsSection
import net.dexxicon.reader.feature.settings.navigation.navigateToAudiobookDefaults
import net.dexxicon.reader.feature.settings.navigation.navigateToBookDefaults
import net.dexxicon.reader.BuildConfig

@Composable
fun DexxiconNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelRoute.Library,
        modifier = modifier,
        // Horizontal slide so the back gesture (incl. predictive back) reveals the previous
        // screen sliding in from the left.
        enterTransition = {
            slideIntoContainer(SlideDirection.Start, tween(320)) +
                fadeIn(tween(320))
        },
        exitTransition = {
            slideOutOfContainer(SlideDirection.Start, tween(320)) +
                fadeOut(tween(320))
        },
        popEnterTransition = {
            slideIntoContainer(SlideDirection.End, tween(320)) +
                fadeIn(tween(320))
        },
        popExitTransition = {
            slideOutOfContainer(SlideDirection.End, tween(320)) +
                fadeOut(tween(320))
        },
    ) {
        composable<TopLevelRoute.Library> {
            LibraryScreen(
                onOpenBook = { serverId, bookId ->
                    navController.navigateToBookDetail(serverId, bookId)
                },
                onContinue = { serverId, bookId, format ->
                    when (format) {
                        ContentFormat.COMIC -> navController.navigateToComicReader(serverId, bookId)
                        ContentFormat.PDF -> navController.navigateToPdfReader(serverId, bookId)
                        ContentFormat.AUDIOBOOK -> navController.navigateToPlayer(serverId, bookId)
                        else -> navController.navigateToEpubReader(serverId, bookId)
                    }
                },
            )
        }

        composable<TopLevelRoute.Browse> {
            BrowseScreen(
                onOpenBook = { book -> navController.navigateToBookDetail(book) },
                onOpenReader = { serverId, bookId, format ->
                    when (format) {
                        ContentFormat.COMIC -> navController.navigateToComicReader(serverId, bookId)
                        ContentFormat.PDF -> navController.navigateToPdfReader(serverId, bookId)
                        ContentFormat.AUDIOBOOK -> navController.navigateToPlayer(serverId, bookId)
                        else -> navController.navigateToEpubReader(serverId, bookId)
                    }
                },
            )
        }

        composable<TopLevelRoute.Settings> {
            SettingsScreen(
                versionName = BuildConfig.VERSION_NAME,
                onAddServer = { navController.navigateToAddServer() },
                onEditServer = { navController.navigateToEditServer(it) },
                onOpenServerCatalog = { id, name -> navController.navigateToCatalog(id, name) },
                onOpenAudiobookDefaults = { navController.navigateToAudiobookDefaults() },
                onOpenBookDefaults = { navController.navigateToBookDefaults() },
            )
        }

        serversSection(navController = navController)
        bookDefaultsSection(navController = navController)

        catalogSection(
            navController = navController,
            onOpenReader = { serverId, bookId, format ->
                when (format) {
                    ContentFormat.COMIC -> navController.navigateToComicReader(serverId, bookId)
                    ContentFormat.PDF -> navController.navigateToPdfReader(serverId, bookId)
                    ContentFormat.AUDIOBOOK -> navController.navigateToPlayer(serverId, bookId)
                    else -> navController.navigateToEpubReader(serverId, bookId)
                }
            },
        )

        epubReaderSection(navController)
        comicReaderSection(navController)
        pdfReaderSection(navController)
        playerSection(navController)
    }
}
