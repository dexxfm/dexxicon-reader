package net.dexxicon.reader.core.media

/**
 * Supplies the audiobook browse tree and playable-item resolution for the media browser
 * (Android Auto / Automotive). Implemented in `:core:data`, which owns the catalog,
 * downloads and reading-progress stores — this keeps `:core:media` a pure playback module,
 * the same split used by [PlaybackProgressSink].
 *
 * All ids are `"serverId::bookId"`. Library nodes are `"lib:<serverId>"`.
 */
interface MediaLibraryContentSource {

    /** Audiobooks in progress — local position ∪ the servers' own "continue listening". */
    suspend fun continueListening(): List<AudiobookCard>

    /** Downloaded audiobooks — playable with no network or auth. */
    suspend fun downloaded(): List<AudiobookCard>

    /** One node per audiobook-carrying server. Empty or size 1 → the caller flattens. */
    suspend fun libraries(): List<LibraryNode>

    /** A page of audiobooks from [serverId] (null = all servers merged). */
    suspend fun audiobooks(serverId: String?, page: Int, pageSize: Int): MediaPage<AudiobookCard>

    /** Free-text audiobook search across every server. */
    suspend fun search(query: String): List<AudiobookCard>

    /** The most recently played audiobook, for the car's resume slot. */
    suspend fun lastPlayed(): AudiobookCard?

    /**
     * Turn a `"serverId::bookId"` id into something playable: the stream URL (or a local
     * file for a downloaded copy), display metadata, and the position to resume at. Also
     * records the book in the reading-progress store so the position write-back has its
     * metadata. Null when it can't be resolved (offline + not downloaded, or no audio).
     */
    suspend fun resolve(mediaId: String): PlayableAudiobook?
}

data class AudiobookCard(
    /** `"serverId::bookId"` */
    val mediaId: String,
    val title: String,
    val author: String?,
    val artworkUri: String?,
    /** 0.0–1.0 listening progress, when known — for the "partially played" badge. */
    val progress: Double? = null,
)

data class LibraryNode(
    /** `"lib:<serverId>"` */
    val id: String,
    val title: String,
)

data class MediaPage<T>(
    val items: List<T>,
    val hasMore: Boolean,
    /** The server rejected us — the browser should offer a "sign in" resolution. */
    val authExpired: Boolean = false,
)

data class PlayableAudiobook(
    val mediaId: String,
    /** Stream URL, or a `file://` URI for a downloaded copy. */
    val uri: String,
    val title: String,
    val author: String?,
    val artworkUri: String?,
    val startPositionMs: Long,
    val durationMs: Long,
    /**
     * A URL a Google Cast receiver can fetch on its own (auth as a `?token=` query param),
     * or null when the source can't be cast — a local `file://` copy, or no token available.
     */
    val castUri: String? = null,
    /** MIME type for the Cast receiver; null lets it sniff. */
    val mimeType: String? = null,
)
