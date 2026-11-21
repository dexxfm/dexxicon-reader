package net.dexxicon.reader.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/**
 * Issue #343 — whether a side safe-area inset on one edge only can mean a column of system
 * bars (the iPhone Duo's outer screen, and its inner screen in landscape), where Apple's
 * guidelines put tab bars and toolbars. Only iOS: on Android a one-sided inset is a cutout or
 * the 3-button navigation bar.
 */
internal expect val platformHasSideBarColumns: Boolean

/**
 * Issue #343 — a system symbol (an SF Symbol on iOS) for controls that sit among the system's
 * own, like the side-column nav beside the status icons. Null where there are none (Android),
 * and the caller falls back to its Material icon.
 */
@Composable
internal expect fun systemSymbol(name: String): Painter?
