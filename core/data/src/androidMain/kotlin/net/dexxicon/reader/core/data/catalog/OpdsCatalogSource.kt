package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.Server
import javax.inject.Inject

/**
 * OPDS 1.2/2.0 browsing for BookOrbit and generic servers. Requires a dedicated OPDS
 * account (HTTP Basic) — the native/OIDC session does not work against OPDS endpoints.
 *
 * TODO(phase 4b): wire readium-opds + per-server OPDS credentials.
 */
class OpdsCatalogSource @Inject constructor() : CatalogSource {

    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = unsupported()

    override suspend fun books(
        server: Server,
        shelfId: String?,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
    ): Outcome<BookPage> = unsupported()

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = unsupported()

    private fun <T> unsupported(): Outcome<T> = Outcome.Failure(
        DexxiconError.Unsupported("OPDS browsing isn't wired up yet — add an OPDS account soon."),
    )
}
