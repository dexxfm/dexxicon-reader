package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.AggregatedBookPage
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookGroupPage
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.SeriesEntry
import net.dexxicon.reader.core.model.SeriesPage
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.seriesKey

/**
 * Merge one page from each server into a de-duplicated Browse page: books that share a
 * normalized title + first author + format collapse into one [AggregatedBook] whose
 * [AggregatedBook.copies] lists every server that carries it.
 */
internal fun mergeAggregated(
    pages: List<Pair<Server, BookPage>>,
    sort: BookSort,
): AggregatedBookPage {
    val merged = LinkedHashMap<String, AggregatedBook>()
    for ((server, bookPage) in pages) {
        for (book in bookPage.books) {
            val key = aggregateKey(book.title, book.authors.firstOrNull(), book.format)
            val copy = BookCopy(server.id, server.displayName, book.id)
            val existing = merged[key]
            merged[key] = if (existing == null) {
                AggregatedBook(
                    title = book.title,
                    authors = book.authors,
                    series = book.series,
                    seriesIndex = book.seriesIndex,
                    coverUrl = book.coverUrl,
                    format = book.format,
                    copies = listOf(copy),
                )
            } else if (existing.copies.any { it.serverId == server.id }) {
                existing // same server returned the book twice — keep one copy
            } else {
                existing.copy(
                    // Prefer the shorter title — servers that append " - Author" lose out.
                    title = if (book.title.length < existing.title.length) book.title else existing.title,
                    copies = existing.copies + copy,
                    coverUrl = existing.coverUrl ?: book.coverUrl,
                )
            }
        }
    }

    val ordered = when (sort) {
        BookSort.RECENT -> merged.values.toList()
        BookSort.TITLE -> merged.values.sortedBy { it.title.lowercase() }
        BookSort.SERIES -> merged.values.sortedWith(
            compareBy({ it.series?.lowercase() ?: "￿" }, { it.seriesIndex ?: Double.MAX_VALUE }),
        )
    }
    return AggregatedBookPage(ordered, hasMore = pages.any { it.second.hasMore })
}

/**
 * issue #256 — merge each server's page of series into one alphabetical list: series with the
 * same name (see [seriesKey]) on several servers become one [SeriesEntry] carrying every
 * server's group, so opening it shows the whole series whichever server holds each volume.
 */
internal fun mergeSeries(pages: List<BookGroupPage>): SeriesPage {
    val merged = LinkedHashMap<String, SeriesEntry>()
    for (group in pages.flatMap { it.groups }) {
        val key = seriesKey(group.name)
        val existing = merged[key]
        merged[key] = if (existing == null) {
            SeriesEntry(name = group.name.trim(), groups = listOf(group), authors = group.authors, coverUrl = group.coverUrl)
        } else if (existing.groups.any { it.key == group.key }) {
            existing
        } else {
            existing.copy(
                groups = existing.groups + group,
                authors = (existing.authors + group.authors).distinct(),
                coverUrl = existing.coverUrl ?: group.coverUrl,
            )
        }
    }
    return SeriesPage(
        series = merged.values.sortedBy { seriesKey(it.name) },
        hasMore = pages.any { it.hasMore },
    )
}
