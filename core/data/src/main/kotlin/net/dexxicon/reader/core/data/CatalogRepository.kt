package net.dexxicon.reader.core.data

import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.catalog.BookOrbitCatalogSource
import net.dexxicon.reader.core.data.catalog.CatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.data.catalog.OpdsCatalogSource
import net.dexxicon.reader.core.data.catalog.mergeAggregated
import net.dexxicon.reader.core.model.AggregatedBookPage
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.CatalogShelf
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    private val serverRepository: ServerRepository,
    private val grimmorySource: GrimmoryCatalogSource,
    private val bookOrbitSource: BookOrbitCatalogSource,
    private val opdsSource: OpdsCatalogSource,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
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
        /** Keep only these content formats; null = all. Applied client-side to the page. */
        formats: Set<ContentFormat>? = null,
    ): Outcome<BookPage> = withContext(io) {
        val (server, source) = resolve(serverId) ?: return@withContext notFound()
        val result = source.books(server, shelfId, query, sort, page, pageSize)
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
                async { server to sourceFor(server).books(server, null, query, sort, page, pageSize) }
            }.awaitAll()
        }

        val pages = results.mapNotNull { (server, outcome) ->
            (outcome as? Outcome.Success)?.value?.let { server to it }
        }
        if (pages.isEmpty()) {
            return@withContext results.firstNotNullOfOrNull { it.second as? Outcome.Failure }
                ?: Outcome.Failure(DexxiconError.Network("Couldn't reach any server"))
        }
        val merged = mergeAggregated(pages, sort)
        Outcome.Success(
            if (formats == null) merged
            else merged.copy(books = merged.books.filter { it.format in formats }),
        )
    }

    private fun <T> notFound(): Outcome<T> =
        Outcome.Failure(DexxiconError.NotFound("Server not found"))

    private companion object {
        const val DEFAULT_PAGE_SIZE = 40
    }
}
