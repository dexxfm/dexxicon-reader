package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookGroupPage
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.Server

/** Browsing backend for one server family. */
interface CatalogSource {

    /** Top-level groupings (libraries / navigation feeds). May be empty for a flat catalog. */
    suspend fun shelves(server: Server): Outcome<List<CatalogShelf>>

    /** A page of books, optionally scoped to a shelf and/or filtered by a search query. */
    suspend fun books(
        server: Server,
        shelfId: String?,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
    ): Outcome<BookPage>

    suspend fun detail(server: Server, bookId: String): Outcome<BookDetail>

    /**
     * Books the user has flagged as wanting to read on the server ("On Deck").
     * Returns an empty list for server families that don't track a reading status.
     */
    suspend fun wantToRead(server: Server): Outcome<List<BookSummary>> =
        Outcome.Success(emptyList())

    /**
     * This server's libraries, collections or smart shelves (issues #253, #254) — every one of
     * [kind] the user can see. [BookGroupKind.SERIES] isn't listed here: a library can hold
     * thousands, so they're paged and searched through [series] instead. Empty for server
     * families without the concept (generic OPDS).
     */
    suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> =
        Outcome.Success(emptyList())

    /** A page of this server's series by name (issue #256), narrowed by [query] if given. */
    suspend fun series(server: Server, query: String?, page: Int, pageSize: Int): Outcome<BookGroupPage> =
        Outcome.Success(BookGroupPage(emptyList(), hasMore = false))

    /**
     * A page of [group]'s books (issues #253, #254, #256). A series always comes back in series
     * order, whatever [sort] says — that order is the point of browsing one. [query] is ignored
     * where the server can't search within that kind of group.
     */
    suspend fun groupBooks(
        server: Server,
        group: BookGroup,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
    ): Outcome<BookPage> = Outcome.Failure(DexxiconError.NotFound("Not supported by this server"))
}
