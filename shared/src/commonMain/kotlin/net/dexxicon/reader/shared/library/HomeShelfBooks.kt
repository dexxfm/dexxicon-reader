package net.dexxicon.reader.shared.library

import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.HomeSection
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.shared.home.HomeUiState

internal fun homeShelfBooks(
    section: HomeSection,
    home: HomeUiState,
    servers: List<Server>,
    state: LibraryUiState,
): List<AggregatedBook> {
    val serversById = servers.associateBy { it.id }
    fun book(
        serverId: String, bookId: String, title: String, authors: List<String>,
        coverUrl: String?, format: ContentFormat, series: String? = null, seriesIndex: Double? = null,
    ): AggregatedBook {
        val server = serversById[serverId]
        return AggregatedBook(
            title, authors, series, seriesIndex, coverUrl, format,
            copies = listOf(BookCopy(
                serverId, server?.displayName ?: serverId, bookId,
                isCatalog = server?.type?.supportsNativeApi == false,
                hasRating = server?.type?.hasRatings ?: true,
                statuses = server?.type?.readingStatuses ?: ReadingStatus.entries,
            )),
        )
    }
    val books = when (section) {
        HomeSection.CONTINUE_READING, HomeSection.CONTINUE_LISTENING ->
            (if (section == HomeSection.CONTINUE_READING) home.continueReading else home.continueListening)
                .map { book(it.serverId, it.bookId, it.title, listOfNotNull(it.author), it.coverUrl, it.format, seriesIndex = it.seriesIndex) }
        HomeSection.ON_DECK -> home.onDeck.map {
            book(it.serverId, it.bookId, it.title, listOfNotNull(it.author), it.coverUrl, it.format, seriesIndex = it.seriesIndex)
        }
        HomeSection.DOWNLOADED -> home.downloads.map {
            book(it.serverId, it.bookId, it.title, it.authors, it.coverUrl, it.format, it.series, it.seriesIndex)
        }
    }.filter { book ->
        (state.filter.formats?.contains(book.format) != false) &&
            (state.query.isBlank() || book.title.contains(state.query, ignoreCase = true) ||
                book.authorLine.contains(state.query, ignoreCase = true))
    }
    return when (state.sort) {
        BookSort.RECENT -> books
        BookSort.TITLE -> books.sortedBy { it.title.lowercase() }
        BookSort.SERIES -> books.sortedWith(compareBy({ it.series?.lowercase() ?: "\uffff" }, { it.seriesIndex ?: Double.MAX_VALUE }))
    }
}
