package net.dexxicon.reader.core.model

/**
 * Where the reader/player left off in one book on one server.
 *
 * [locator] is an opaque, format-specific position token — for EPUB/PDF/comic it is a
 * Readium `Locator` serialised to JSON; for audiobooks it is `{"position":<ms>}`. The app
 * never interprets it outside the matching reader.
 */
data class ReadingProgress(
    val serverId: String,
    val bookId: String,
    /** 0.0–1.0 overall progression, for progress rings and sync. Null if unknown. */
    val percent: Double? = null,
    val locator: String? = null,
    val updatedAt: Long = 0L,
) {
    val key: String get() = "$serverId::$bookId"
}
