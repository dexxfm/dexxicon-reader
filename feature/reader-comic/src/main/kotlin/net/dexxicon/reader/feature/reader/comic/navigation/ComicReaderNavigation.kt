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

/**
 * [onExit] is the fallback for when there's nothing left in [navController]'s own back stack
 * to pop — the case since Phase 4 Stage I (issue #146), when this became `ReaderActivity`'s
 * *sole* destination rather than one stop in a bigger app-wide NavHost. `popBackStack()`
 * alone silently no-ops there (issue #155): the hardware/gesture back button still worked
 * (Android's own dispatcher falls through to finishing the Activity when the NavHost has
 * nothing to pop), but the in-screen back arrow did nothing at all.
 */
fun NavGraphBuilder.comicReaderSection(navController: NavController, onExit: () -> Unit) {
    composable<ComicReaderRoute> {
        ComicReaderScreen(onBack = { if (!navController.popBackStack()) onExit() })
    }
}
