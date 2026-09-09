package net.dexxicon.reader.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import net.dexxicon.reader.R

enum class TopLevelDestination(
    val route: TopLevelRoute,
    val icon: ImageVector,
    val labelRes: Int,
) {
    // Route names are historical: "Library" is the Home tab, "Browse" is the Library tab.
    LIBRARY(TopLevelRoute.Library, Icons.Filled.Home, R.string.nav_library),
    BROWSE(TopLevelRoute.Browse, Icons.AutoMirrored.Filled.LibraryBooks, R.string.nav_browse),
    SETTINGS(TopLevelRoute.Settings, Icons.Filled.Settings, R.string.nav_settings),
}
