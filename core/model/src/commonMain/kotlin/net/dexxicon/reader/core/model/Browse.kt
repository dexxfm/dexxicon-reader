package net.dexxicon.reader.core.model

/** A top-level grouping within a server (a BookLore library, an OPDS navigation feed…). */
data class CatalogShelf(
    val id: String,
    val title: String,
    val bookCount: Int? = null,
)

/** One page of book results. */
data class BookPage(
    val books: List<BookSummary>,
    val page: Int,
    val hasMore: Boolean,
    val total: Int? = null,
)

/** One server's copy of a book, as merged into an [AggregatedBook]. */
data class BookCopy(
    val serverId: String,
    val serverName: String,
    val bookId: String,
)

/**
 * A book as shown in the merged Browse list: one entry per (title, author, format),
 * with every server that carries it listed in [copies].
 */
data class AggregatedBook(
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    val coverUrl: String? = null,
    val format: ContentFormat = ContentFormat.UNKNOWN,
    val copies: List<BookCopy> = emptyList(),
) {
    val authorLine: String get() = authors.joinToString(", ")

    /** Stable list key across recompositions / reorders. */
    val key: String get() = copies.joinToString(",") { "${it.serverId}:${it.bookId}" }

    val primary: BookCopy get() = copies.first()
}

/** One page of merged Browse results. */
data class AggregatedBookPage(
    val books: List<AggregatedBook>,
    val hasMore: Boolean,
)

/** A book/comic/audiobook as shown in a grid — enough to render a card and open detail. */
data class BookSummary(
    val id: String,
    val serverId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    val coverUrl: String? = null,
    val format: ContentFormat = ContentFormat.UNKNOWN,
    val shelfId: String? = null,
) {
    val authorLine: String get() = authors.joinToString(", ")
}

/** Full detail for the book detail sheet. */
data class BookDetail(
    val summary: BookSummary,
    val description: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val pageCount: Int? = null,
    val narrators: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val acquisitions: List<Acquisition> = emptyList(),
    /** The primary file's extension, lower-case, no dot (e.g. `epub`, `cbz`, `m4b`). */
    val fileExtension: String? = null,
    val fileSizeBytes: Long? = null,
    /** Populated for [ContentFormat.AUDIOBOOK] — chapters + total duration. */
    val audio: AudiobookInfo? = null,
    /** The server's per-user reading status, if it has one set. */
    val readingStatus: ReadingStatus? = null,
) {
    val narratorLine: String get() = narrators.joinToString(", ")

    val primaryAcquisition: Acquisition?
        get() = acquisitions.minByOrNull { it.format.priority }
}

data class AudiobookInfo(
    val durationMs: Long,
    val chapters: List<Chapter> = emptyList(),
)

/** How book listings are ordered. */
enum class BookSort { RECENT, TITLE, SERIES }
