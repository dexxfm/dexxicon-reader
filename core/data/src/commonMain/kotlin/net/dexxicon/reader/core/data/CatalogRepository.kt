package net.dexxicon.reader.core.data

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.catalog.BookOrbitCatalogSource
import net.dexxicon.reader.core.data.catalog.CatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.data.catalog.OpdsCatalogSource
import net.dexxicon.reader.core.data.catalog.mergeAggregated
import net.dexxicon.reader.core.data.catalog.mergeSeries
import net.dexxicon.reader.core.model.AggregatedBookPage
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.SeriesPage
import net.dexxicon.reader.core.model.seriesKey
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [io] is a plain, unqualified [CoroutineDispatcher] rather than `@Dispatcher(IO)` — that
 * qualifier annotation is `javax.inject`-based (Hilt/androidMain-only) and can't live in
 * commonMain; the `:app`-hosted provider resolves the qualified binding and passes the
 * instance through instead, same as every other Phase 1/2 class with this shape.
 */
class CatalogRepository(
    private val serverRepository: ServerRepository,
    private val grimmorySource: GrimmoryCatalogSource,
    private val bookOrbitSource: BookOrbitCatalogSource,
    private val opdsSource: OpdsCatalogSource,
    private val io: CoroutineDispatcher,
) {
    /** The configured server ids — Browse re-queries whenever this changes (add / remove). */
    val serverIds: Flow<Set<String>> =
        serverRepository.servers.map { list -> list.map { it.id }.toSet() }

    private fun sourceFor(server: Server): CatalogSource = when (server.type) {
        ServerType.GRIMMORY -> grimmorySource
        ServerType.BOOKORBIT -> bookOrbitSource
        else -> opdsSource
    }

    private suspend fun resolve(serverId: String): Pair<Server, CatalogSource>? {
        val server = serverRepository.get(serverId) ?: return null
        return server to sourceFor(server)
    }

    suspend fun shelves(serverId: String): Outcome<List<CatalogShelf>> = withContext(io) {
        val (server, source) = resolve(serverId) ?: return@withContext notFound()
        source.shelves(server)
    }

    suspend fun books(
        serverId: String,
        shelfId: String?,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        /** Keep only these content formats; null = all. Passed to the source (issue #287) and
         *  applied client-side to the page too. */
        formats: Set<ContentFormat>? = null,
    ): Outcome<BookPage> = withContext(io) {
        val (server, source) = resolve(serverId) ?: return@withContext notFound()
        val result = source.books(server, shelfId, query, sort, page, pageSize, formats)
        when {
            formats == null -> result
            result is Outcome.Success -> Outcome.Success(
                result.value.copy(books = result.value.books.filter { it.format in formats }),
            )
            else -> result
        }
    }

    suspend fun detail(serverId: String, bookId: String): Outcome<BookDetail> = withContext(io) {
        val (server, source) = resolve(serverId) ?: return@withContext notFound()
        source.detail(server, bookId)
    }

    /**
     * The merged Browse list: page [page] from every configured server, fetched
     * concurrently, then de-duplicated by (title, first author, format). Every server
     * that carries a book is recorded in [AggregatedBook.copies].
     *
     * Ordering is approximate: each server is asked for its own page [page] in the
     * requested [sort], and the merged result is re-sorted — books further down one
     * server's catalogue can still surface a page early. Search narrows this in practice.
     */
    suspend fun allBooks(
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        /** Keep only these content formats; null = all. Applied to the merged result. */
        formats: Set<ContentFormat>? = null,
    ): Outcome<AggregatedBookPage> = withContext(io) {
        val servers = serverRepository.servers.first()
        if (servers.isEmpty()) {
            return@withContext Outcome.Success(AggregatedBookPage(emptyList(), hasMore = false))
        }

        val results = coroutineScope {
            servers.map { server ->
                async { server to sourceFor(server).books(server, null, query, sort, page, pageSize, formats) }
            }.awaitAll()
        }

        val pages = results.mapNotNull { (server, outcome) ->
            (outcome as? Outcome.Success)?.value?.let { server to it }
        }
        if (pages.isEmpty()) {
            return@withContext results.firstNotNullOfOrNull { it.second as? Outcome.Failure }
                ?: Outcome.Failure(DexxiconError.Network("Couldn't reach any server"))
        }
        val merged = mergeAggregated(pages, sort).copy(
            // issue #293 — with a mix of servers the app's sort still orders some of them.
            appSortApplies = pages.any { (_, p) -> p.appSortApplies },
        )
        Outcome.Success(
            if (formats == null) merged
            else merged.copy(books = merged.books.filter { it.format in formats }),
        )
    }

    /**
     * The "On Deck" shelf: every configured server's "want to read" books, fetched
     * concurrently and de-duplicated by (title, author) so a book on two servers shows once.
     * A single server that fails (or doesn't track a reading status) just contributes nothing;
     * only when *every* server failed is this a [Outcome.Failure] — so the caller can keep the
     * last good list rather than blanking the shelf.
     */
    suspend fun onDeck(): Outcome<List<BookSummary>> = withContext(io) {
        val servers = serverRepository.servers.first()
        if (servers.isEmpty()) return@withContext Outcome.Success(emptyList())
        val results = coroutineScope {
            servers.map { server -> async { sourceFor(server).wantToRead(server) } }.awaitAll()
        }
        if (results.none { it is Outcome.Success }) {
            return@withContext results.firstNotNullOfOrNull { it as? Outcome.Failure }
                ?: Outcome.Failure(DexxiconError.Network("Couldn't reach any server"))
        }
        val books = results.mapNotNull { (it as? Outcome.Success)?.value }.flatten()
        Outcome.Success(
            books.distinctBy { "${it.title.lowercase()}|${it.authorLine.lowercase()}" },
        )
    }

    /**
     * issues #253/#254 — every server's libraries, collections or smart shelves ([kind]), in
     * server priority order. A server that fails just contributes nothing; only when *every*
     * server failed is this a [Outcome.Failure], same rule as [onDeck].
     */
    suspend fun groups(kind: BookGroupKind): Outcome<List<BookGroup>> = withContext(io) {
        val servers = serverRepository.servers.first()
        if (servers.isEmpty()) return@withContext Outcome.Success(emptyList())
        val results = coroutineScope {
            servers.map { server -> async { sourceFor(server).groups(server, kind) } }.awaitAll()
        }
        if (results.none { it is Outcome.Success }) {
            return@withContext results.firstNotNullOfOrNull { it as? Outcome.Failure }
                ?: Outcome.Failure(DexxiconError.Network("Couldn't reach any server"))
        }
        Outcome.Success(results.mapNotNull { (it as? Outcome.Success)?.value }.flatten())
    }

    /**
     * issue #256 — page [page] of every server's series, merged by name (see [mergeSeries]).
     * Same approximate cross-server paging as [allBooks]: a series one server lists on page 0
     * can still turn up from another server on page 1, so callers merge each new page into
     * what they already have by [SeriesEntry][net.dexxicon.reader.core.model.SeriesEntry] name.
     */
    suspend fun series(query: String?, page: Int, pageSize: Int = DEFAULT_PAGE_SIZE): Outcome<SeriesPage> =
        withContext(io) {
            val servers = serverRepository.servers.first()
            if (servers.isEmpty()) return@withContext Outcome.Success(SeriesPage(emptyList(), hasMore = false))
            val results = coroutineScope {
                servers.map { server -> async { sourceFor(server).series(server, query, page, pageSize) } }.awaitAll()
            }
            val pages = results.mapNotNull { (it as? Outcome.Success)?.value }
            if (pages.isEmpty()) {
                return@withContext results.firstNotNullOfOrNull { it as? Outcome.Failure }
                    ?: Outcome.Failure(DexxiconError.Network("Couldn't reach any server"))
            }
            Outcome.Success(mergeSeries(pages))
        }

    /**
     * issue #256 — every server's series called exactly [name] (case/whitespace-insensitive),
     * for opening a series from a book's own series line, where all that's known is its name.
     * Found by searching each server for it, since BookOrbit addresses series by id.
     */
    suspend fun seriesNamed(name: String): List<BookGroup> = withContext(io) {
        val key = seriesKey(name)
        val servers = serverRepository.servers.first()
        coroutineScope {
            servers.map { server ->
                async {
                    (sourceFor(server).series(server, name, 0, SERIES_LOOKUP_SIZE) as? Outcome.Success)
                        ?.value?.groups.orEmpty()
                        .filter { seriesKey(it.name) == key }
                }
            }.awaitAll()
        }.flatten()
    }

    /**
     * issues #253/#254/#256 — page [page] of the books in [groups], merged across servers the
     * same way [allBooks] merges the whole catalogue. Usually one group; a series spans one per
     * server that has it. Series pages stay in series order.
     */
    suspend fun groupBooks(
        groups: List<BookGroup>,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        formats: Set<ContentFormat>? = null,
        /** issue #293 — a catalog facet to list instead (only meaningful for one group). */
        facetHref: String? = null,
    ): Outcome<AggregatedBookPage> = withContext(io) {
        if (groups.isEmpty()) return@withContext Outcome.Success(AggregatedBookPage(emptyList(), hasMore = false))
        val facet = facetHref.takeIf { groups.size == 1 }
        val results = coroutineScope {
            groups.map { group ->
                async {
                    val server = serverRepository.get(group.serverId)
                        ?: return@async null to notFound<BookPage>()
                    server to sourceFor(server).groupBooks(server, group, query, sort, page, pageSize, formats, facet)
                }
            }.awaitAll()
        }
        val pages = results.mapNotNull { (server, outcome) ->
            val value = (outcome as? Outcome.Success)?.value
            if (server != null && value != null) server to value else null
        }
        if (pages.isEmpty()) {
            return@withContext results.firstNotNullOfOrNull { it.second as? Outcome.Failure }
                ?: Outcome.Failure(DexxiconError.Network("Couldn't reach any server"))
        }
        val isSeries = groups.all { it.kind == BookGroupKind.SERIES }
        val merged = mergeAggregated(pages, if (isSeries) BookSort.SERIES else sort).copy(
            // issue #293 — one catalog's own sort/filter choices, when the list is one catalog's.
            facets = pages.singleOrNull()?.second?.facets.orEmpty(),
            appSortApplies = pages.any { (_, p) -> p.appSortApplies },
        )
        Outcome.Success(
            if (formats == null) merged
            else merged.copy(books = merged.books.filter { it.format in formats }),
        )
    }

    private fun <T> notFound(): Outcome<T> =
        Outcome.Failure(DexxiconError.NotFound("Server not found"))

    private companion object {
        const val DEFAULT_PAGE_SIZE = 40
        const val SERIES_LOOKUP_SIZE = 20
    }
}
