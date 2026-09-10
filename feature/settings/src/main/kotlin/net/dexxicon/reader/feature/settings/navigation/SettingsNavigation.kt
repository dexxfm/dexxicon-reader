package net.dexxicon.reader.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.settings.AudiobookDefaultsScreen
import net.dexxicon.reader.feature.settings.BookDefaultsScreen

@Serializable object AudiobookDefaultsRoute

/** EPUB, comic and PDF defaults, condensed into one screen grouped by format. */
@Serializable object BookDefaultsRoute

fun NavController.navigateToAudiobookDefaults() = navigate(AudiobookDefaultsRoute)
fun NavController.navigateToBookDefaults() = navigate(BookDefaultsRoute)

/** Settings › Book Defaults sub-screens. */
fun NavGraphBuilder.bookDefaultsSection(navController: NavController) {
    composable<AudiobookDefaultsRoute> {
        AudiobookDefaultsScreen(onBack = { navController.popBackStack() })
    }
    composable<BookDefaultsRoute> {
        BookDefaultsScreen(onBack = { navController.popBackStack() })
    }
}
