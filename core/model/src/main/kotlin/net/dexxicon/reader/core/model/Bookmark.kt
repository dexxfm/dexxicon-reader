package net.dexxicon.reader.core.model

/**
 * A saved position in an EPUB. [locatorJson] is a Readium `Locator` serialised to JSON —
 * the position within the publication. A bookmark created here is packed into the server's
 * `cfi` field so it round-trips; a bookmark made in a server's web reader comes back as a
 * real EPUB CFI and is kept in the list but jumps only to its chapter.
 */
data class Bookmark(
    val id: String,
    val serverId: String,
    val bookId: String,
    val locatorJson: String,
    /** 0.0–1.0 position in the whole book, for ordering the list. */
    val progression: Double,
    /** Chapter title, shown as the bookmark's label. */
    val title: String,
    /**
     * The raw EPUB CFI when this bookmark was made in a server's web reader (not by us).
     * Null for our own bookmarks, whose position lives in [locatorJson]. A foreign bookmark
     * jumps only to its chapter.
     */
    val foreignCfi: String? = null,
    val createdAt: Long = 0L,
    /** Server bookmark id once synced. */
    val remoteId: String? = null,
    /** Local, not yet pushed. */
    val dirty: Boolean = true,
) {
    /** Ours (precise) vs pulled from a web reader (chapter-only). */
    val isForeign: Boolean get() = foreignCfi != null
}
