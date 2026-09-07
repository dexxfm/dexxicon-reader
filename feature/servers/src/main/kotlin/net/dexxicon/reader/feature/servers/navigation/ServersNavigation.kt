package net.dexxicon.reader.feature.servers.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.servers.AddEditServerScreen

@Serializable
data class AddEditServerRoute(val serverId: String? = null)

fun NavController.navigateToAddServer() = navigate(AddEditServerRoute())

fun NavController.navigateToEditServer(serverId: String) =
    navigate(AddEditServerRoute(serverId))

/** Add / edit a server. The server *list* lives in Settings, not its own screen. */
fun NavGraphBuilder.serversSection(navController: NavController) {
    composable<AddEditServerRoute> { entry ->
        val route = entry.toRoute<AddEditServerRoute>()
        AddEditServerScreen(
            serverId = route.serverId,
            onDone = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
}
