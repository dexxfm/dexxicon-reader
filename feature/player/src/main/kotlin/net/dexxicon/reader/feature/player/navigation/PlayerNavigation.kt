package net.dexxicon.reader.feature.player.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.player.PlayerScreen

@Serializable
data class PlayerRoute(val serverId: String, val bookId: String)

fun NavController.navigateToPlayer(serverId: String, bookId: String) =
    navigate(PlayerRoute(serverId, bookId))

fun NavGraphBuilder.playerSection(navController: NavController) {
    composable<PlayerRoute> {
        PlayerScreen(onBack = { navController.popBackStack() })
    }
}
