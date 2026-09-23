package net.dexxicon.reader.core.designsystem.component

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which optional overlays [CoverImage] draws. App-wide user settings, provided once at the
 * app root (`shared/App.kt`) rather than threaded through every screen's parameters, since
 * every cover everywhere has to honour them the same way.
 */
data class CoverBadges(
    /** issue #252 — the colour-coded format tag, bottom-right. */
    val showFormat: Boolean = true,
)

val LocalCoverBadges = staticCompositionLocalOf { CoverBadges() }
