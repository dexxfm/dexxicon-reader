package net.dexxicon.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.dexxicon.reader.feature.player.navigation.PlayerRoute
import net.dexxicon.reader.feature.player.navigation.navigateToPlayer
import net.dexxicon.reader.navigation.DexxiconNavHost
import net.dexxicon.reader.navigation.TopLevelDestination

@Composable
fun DexxiconApp(shellViewModel: AppShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination: NavDestination? = backStackEntry?.destination
    val playback by shellViewModel.playback.collectAsStateWithLifecycle()

    val currentTopLevel = TopLevelDestination.entries.firstOrNull { destination ->
        currentDestination.isOn(destination)
    }
    val onPlayerScreen = currentDestination?.hasRoute(PlayerRoute::class) == true

    Scaffold(
        bottomBar = {
            Column {
                if (playback.audiobook != null && !onPlayerScreen) {
                    MiniPlayer(
                        playback = playback,
                        onOpen = { serverId, bookId -> navController.navigateToPlayer(serverId, bookId) },
                        onPlayPause = shellViewModel::playPause,
                        onDismiss = shellViewModel::dismiss,
                    )
                }
                if (currentTopLevel != null) {
                    NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == currentTopLevel,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
                }
            }
        },
    ) { innerPadding ->
        DexxiconNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.route::class) } == true
