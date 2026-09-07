package net.dexxicon.reader.navigation

import kotlinx.serialization.Serializable

/** Top-level (bottom-bar / rail) destinations. */
sealed interface TopLevelRoute {
    @Serializable data object Library : TopLevelRoute
    @Serializable data object Browse : TopLevelRoute
    @Serializable data object Settings : TopLevelRoute
}
