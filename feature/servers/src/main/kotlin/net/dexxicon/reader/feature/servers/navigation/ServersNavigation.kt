package net.dexxicon.reader.feature.servers.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.dexxicon.reader.feature.catalog.navigation.navigateToCatalog
import net.dexxicon.reader.feature.servers.AddEditServerScreen
import net.dexxicon.reader.feature.servers.ServersScreen

/** Phase 4 Stage G (issue #144) — the server list itself, previously embedded inline inside
 * `SettingsScreen.kt`. Reached from Settings' "Manage servers" row. */
@Serializable
object ManageServersRoute

@Serializable
data class AddEditServerRoute(
    val serverId: String? = null,
    /** Open straight into SSO sign-in for an existing server whose session expired. */
    val reauth: Boolean = false,
)

fun NavController.navigateToManageServers() = navigate(ManageServersRoute)

fun NavController.navigateToAddServer() = navigate(AddEditServerRoute())

fun NavController.navigateToEditServer(serverId: String) =
    navigate(AddEditServerRoute(serverId))

fun NavController.navigateToReauthServer(serverId: String) =
    navigate(AddEditServerRoute(serverId, reauth = true))

fun NavGraphBuilder.serversSection(navController: NavController) {
    composable<ManageServersRoute> {
        ServersScreen(
            onBack = { navController.popBackStack() },
            onAddServer = { navController.navigateToAddServer() },
            onEditServer = { navController.navigateToEditServer(it) },
            onOpenServer = { serverId -> navController.navigateToCatalog(serverId) },
        )
    }
    composable<AddEditServerRoute> { entry ->
        val route = entry.toRoute<AddEditServerRoute>()
        AddEditServerScreen(
            serverId = route.serverId,
            reauth = route.reauth,
            onDone = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }
}
