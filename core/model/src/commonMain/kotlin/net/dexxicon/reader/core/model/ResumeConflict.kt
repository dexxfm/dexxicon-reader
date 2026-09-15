package net.dexxicon.reader.core.model

/**
 * issue #216 — surfaced when an audiobook's current position and what the server reports
 * disagree by more than a few seconds, so the player can ask instead of silently picking a
 * side. Silently picking was the actual bug: a live/backgrounded session kept trusting its
 * own cached position and periodically pushed it straight back over a position the server
 * had just been reset to (e.g. "mark as unread") — never surfacing that the two disagreed
 * at all. Lives in `core:model` (not `:shared`) so both the Android player's own ViewModel
 * (`feature:player`, no `:shared` dependency) and the shared player screen can reference the
 * same type without a new cross-module edge.
 */
data class ResumeConflict(
    val serverPositionMs: Long,
    val localPositionMs: Long,
    val durationMs: Long,
)
