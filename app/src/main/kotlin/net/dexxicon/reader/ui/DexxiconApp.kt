package net.dexxicon.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.crash.CrashReportSheet
import net.dexxicon.reader.crash.shareCrashReport
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.dexxicon.reader.feature.player.navigation.PlayerRoute
import net.dexxicon.reader.feature.player.navigation.navigateToPlayer
import net.dexxicon.reader.feature.servers.navigation.navigateToReauthServer
import net.dexxicon.reader.navigation.DexxiconNavHost
import net.dexxicon.reader.navigation.TopLevelDestination

/**
 * At or above this width the side navigation rail replaces the bottom bar — Material's
 * "medium" window-width class, which covers most phones in landscape plus tablets and
 * unfolded foldables.
 */
private val RAIL_BREAKPOINT = 600.dp

@Composable
fun DexxiconApp(shellViewModel: AppShellViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination: NavDestination? = backStackEntry?.destination
    val playback by shellViewModel.playback.collectAsStateWithLifecycle()
    val signInPrompts by shellViewModel.signInPrompts.collectAsStateWithLifecycle()
    val pendingCrash by shellViewModel.pendingCrash.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    pendingCrash?.let { report ->
        CrashReportSheet(
            report = report,
            onSend = { note ->
                shareCrashReport(context, report, note)
                shellViewModel.dismissCrash(delete = true)
            },
            onKeep = { shellViewModel.dismissCrash(delete = false) },
            onDiscard = { shellViewModel.dismissCrash(delete = true) },
        )
    }

    LaunchedEffect(Unit) {
        shellViewModel.reauthRequests.collect { serverId ->
            navController.navigateToReauthServer(serverId)
        }
    }
    LaunchedEffect(Unit) {
        shellViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val currentTopLevel = TopLevelDestination.entries.firstOrNull { destination ->
        currentDestination.isOn(destination)
    }
    val onPlayerScreen = currentDestination?.hasRoute(PlayerRoute::class) == true
    val miniPlayerVisible = playback.audiobook != null && !onPlayerScreen

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= RAIL_BREAKPOINT
        val showRail = wide && currentTopLevel != null
        val showBottomBar = !wide && currentTopLevel != null

        Scaffold(
            // Each destination has its own Scaffold + TopAppBar that consumes the status-bar
            // inset; without this the shell would add it a second time above every screen.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    if (miniPlayerVisible) {
                        MiniPlayer(
                            playback = playback,
                            onOpen = { serverId, bookId -> navController.navigateToPlayer(serverId, bookId) },
                            onPlayPause = shellViewModel::playPause,
                            onDismiss = shellViewModel::dismiss,
                        )
                    }
                    if (showBottomBar) {
                        NavigationBar {
                            TopLevelDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = destination == currentTopLevel,
                                    onClick = { navController.switchTopLevel(destination) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(stringResource(destination.labelRes)) },
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
                if (showRail) {
                    NavigationRail {
                        TopLevelDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = destination == currentTopLevel,
                                onClick = { navController.switchTopLevel(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxSize()) {
                    signInPrompts.forEach { prompt ->
                        SignInBanner(
                            displayName = prompt.displayName,
                            onClick = { navController.navigateToReauthServer(prompt.serverId) },
                        )
                    }
                    // The banner already sits below the status bar; without this the screen
                    // under it would add the status-bar inset a second time.
                    val hostModifier = if (signInPrompts.isEmpty()) {
                        Modifier
                    } else {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    }
                    DexxiconNavHost(
                        navController = navController,
                        modifier = hostModifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignInBanner(displayName: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Sign in to $displayName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Its session expired — tap to sign in again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun NavHostController.switchTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.route::class) } == true
