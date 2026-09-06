package net.dexxicon.reader.core.media

/**
 * Where the player writes listening position. Implemented in `:core:data` (which owns the
 * reading-progress store) so `:core:media` stays a pure playback module.
 */
interface PlaybackProgressSink {
    suspend fun save(serverId: String, bookId: String, positionMs: Long, percent: Double?)
}
