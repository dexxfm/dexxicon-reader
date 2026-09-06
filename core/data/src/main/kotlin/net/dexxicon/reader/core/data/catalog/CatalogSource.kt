package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
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
}
