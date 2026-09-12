package net.dexxicon.reader.shared.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Phase 4 (issue #115) — `:shared`'s equivalent of the native app's
 * `navigation/TopLevelDestination.kt`: the three destinations the adaptive shell
 * ([net.dexxicon.reader.shared.AppShell]) hosts behind [net.dexxicon.reader.shared.nav.FloatingPillNavBar] /
 * [net.dexxicon.reader.shared.nav.PillNavigationRail]. Unlike native, there's no
 * `stringResource`/`R.string` in commonMain, so labels are plain strings here.
 */
enum class TopLevelDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    LIBRARY("Library", Icons.AutoMirrored.Filled.LibraryBooks),
    SETTINGS("Settings", Icons.Filled.Settings),
}
