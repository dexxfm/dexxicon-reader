package net.dexxicon.reader.core.data

import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.catalog.CatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.data.catalog.OpdsCatalogSource
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    private val serverRepository: ServerRepository,
    private val grimmorySource: GrimmoryCatalogSource,
    private val opdsSource: OpdsCatalogSource,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private suspend fun resolve(serverId: String): Pair<Server, CatalogSource>? {
        val server = serverRepository.get(serverId) ?: return null
        val source = when (server.type) {
            ServerType.GRIMMORY -> grimmorySource
            else -> opdsSource
        }
        return server to source
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
    ): Outcome<BookPage> = withContext(io) {
        val (server, source) = resolve(serverId) ?: return@withContext notFound()
        source.books(server, shelfId, query, sort, page, pageSize)
    }

    suspend fun detail(serverId: String, bookId: String): Outcome<BookDetail> = withContext(io) {
        val (server, source) = resolve(serverId) ?: return@withContext notFound()
        source.detail(server, bookId)
    }

    private fun <T> notFound(): Outcome<T> =
        Outcome.Failure(DexxiconError.NotFound("Server not found"))

    private companion object {
        const val DEFAULT_PAGE_SIZE = 40
    }
}
