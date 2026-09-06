package net.dexxicon.reader.navigation

import kotlinx.serialization.Serializable

/** Top-level (bottom-bar) destinations. */
sealed interface TopLevelRoute {
    @Serializable data object Library : TopLevelRoute
    @Serializable data object Browse : TopLevelRoute
    @Serializable data object Settings : TopLevelRoute
}

/** Detail / full-screen destinations. */
@Serializable data object ServersRoute

@Serializable data class CatalogFeedRoute(val serverId: String, val feedUrl: String? = null)

@Serializable data class ReaderRoute(val itemId: String)

@Serializable data class ComicReaderRoute(val itemId: String)

@Serializable data class PdfReaderRoute(val itemId: String)

@Serializable data class PlayerRoute(val itemId: String)

@Serializable data class AnnotationsRoute(val itemId: String)
