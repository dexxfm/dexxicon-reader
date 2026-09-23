package net.dexxicon.reader.core.model

/**
 * Where the reader/player left off in one book on one server.
 *
 * [locator] is an opaque, format-specific position token — for EPUB/PDF/comic it is a
 * Readium `Locator` serialised to JSON; for audiobooks it is `{"position":<ms>}`. The app
 * never interprets it outside the matching reader.
 *
 * The metadata fields ([title], [author], [coverUrl], [format], [digestUrl]) are a snapshot
 * captured when a reader/player opens the book, so the Library's "Continue" shelves can be
 * rendered — and KOReader progress refreshed — without a catalog round-trip.
 */
data class ReadingProgress(
    val serverId: String,
    val bookId: String,
    /** 0.0–1.0 overall progression, for progress rings and sync. Null if unknown. */
    val percent: Double? = null,
    val locator: String? = null,
    val updatedAt: Long = 0L,
    val title: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val format: ContentFormat? = null,
    /** Remote URL whose bytes yield this book's KOReader digest (for stream-only books). */
    val digestUrl: String? = null,
    /** issue #257 — cached like [title]/[coverUrl] so Home's shelves can show a book's
     *  series number without a catalog round-trip. */
    val series: String? = null,
    val seriesIndex: Double? = null,
) {
    val key: String get() = "$serverId::$bookId"

    /** In progress: started but not finished. */
    val isInProgress: Boolean get() = (percent ?: 0.0).let { it > 0.0 && it < 0.985 }
}
