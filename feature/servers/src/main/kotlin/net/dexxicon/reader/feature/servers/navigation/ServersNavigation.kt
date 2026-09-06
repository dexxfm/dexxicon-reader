package net.dexxicon.reader.feature.servers.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.servers.AddEditServerScreen
import net.dexxicon.reader.feature.servers.ServersScreen

@Serializable
data object ServersRoute

@Serializable
data class AddEditServerRoute(val serverId: String? = null)

fun NavController.navigateToServers() = navigate(ServersRoute)

fun NavController.navigateToAddServer() = navigate(AddEditServerRoute())

fun NavController.navigateToEditServer(serverId: String) =
    navigate(AddEditServerRoute(serverId))

fun NavGraphBuilder.serversSection(
    onBack: () -> Unit,
    navController: NavController,
) {
    composable<ServersRoute> {
        ServersScreen(
            onBack = onBack,
            onAddServer = { navController.navigateToAddServer() },
            onEditServer = { navController.navigateToEditServer(it) },
        )
    }
    composable<AddEditServerRoute> { entry ->
        val route = entry.toRoute<AddEditServerRoute>()
        AddEditServerScreen(
            serverId = route.serverId,
            onDone = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
}
