package net.dexxicon.reader.core.model

/** The chips above a book list. Each maps to the content formats it keeps; null = keep all. */
enum class ContentFilter(val label: String, val formats: Set<ContentFormat>?) {
    ALL("All", null),
    BOOKS("Books", setOf(ContentFormat.EPUB)),
    COMICS("Comics", setOf(ContentFormat.COMIC)),
    AUDIOBOOKS("Audiobooks", setOf(ContentFormat.AUDIOBOOK)),
    PDFS("PDFs", setOf(ContentFormat.PDF)),
    OTHER(
        "Other",
        setOf(ContentFormat.FB2, ContentFormat.MOBI, ContentFormat.AZW3, ContentFormat.UNKNOWN),
    ),
}

/** How a book list renders. */
enum class BookViewMode { LIST, GRID }
