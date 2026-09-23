package net.dexxicon.reader.shared

/**
 * issue #277 — KOReader sync isn't ready to offer for 1.2.0, so its setup UI is hidden: Add/Edit
 * server's "KOReader sync (optional)" row and Settings › Reading sync's KOReader account card.
 * Only the UI: the sync itself is untouched, so an account set up earlier keeps syncing.
 * Flip to true to bring both back.
 */
internal const val KOREADER_SYNC_UI = false
