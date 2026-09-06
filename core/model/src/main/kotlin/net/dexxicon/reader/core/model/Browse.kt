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
    val categories: List<String> = emptyList(),
    val acquisitions: List<Acquisition> = emptyList(),
    val fileSizeBytes: Long? = null,
) {
    val primaryAcquisition: Acquisition?
        get() = acquisitions.minByOrNull { it.format.priority }
}

/** How book listings are ordered. */
enum class BookSort { RECENT, TITLE, SERIES }
