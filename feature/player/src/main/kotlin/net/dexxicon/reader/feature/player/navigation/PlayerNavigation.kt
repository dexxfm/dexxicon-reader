package net.dexxicon.reader.feature.player.navigation

import kotlinx.serialization.Serializable

/** issue #183: the destination itself now lives in `:app`'s `ReaderActivity` (rendering the
 * shared `net.dexxicon.reader.shared.player.PlayerScreen`) — this route stays here since
 * [net.dexxicon.reader.feature.player.PlayerViewModel] (kept for its `init{}` side effect: it
 * resolves the book and starts real playback) still reads it via `SavedStateHandle.toRoute()`. */
@Serializable
data class PlayerRoute(val serverId: String, val bookId: String)
