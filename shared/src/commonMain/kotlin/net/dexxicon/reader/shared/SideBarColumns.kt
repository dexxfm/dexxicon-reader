package net.dexxicon.reader.shared

/**
 * Issue #343 — whether a side safe-area inset on one edge only can mean a column of system
 * bars (the iPhone Duo's outer screen, and its inner screen in landscape), where Apple's
 * guidelines put tab bars and toolbars. Only iOS: on Android a one-sided inset is a cutout or
 * the 3-button navigation bar.
 */
internal expect val platformHasSideBarColumns: Boolean

/**
 * Issue #343 — where the status bar runs down the side, its bottom edge in points from the top
 * of the screen, so the side column's controls can start right below it (Apple's order: the
 * Dynamic Island, the status bar, then toolbars and tab bars). Null when the status bar runs
 * across the top, or the platform has no such thing.
 */
internal expect fun sideStatusBarBottom(): Double?
