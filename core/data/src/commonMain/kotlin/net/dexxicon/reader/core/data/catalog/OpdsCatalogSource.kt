package net.dexxicon.reader.core.data.catalog

import io.ktor.client.plugins.ResponseException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Acquisition
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.CatalogShelf
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Facet
import net.dexxicon.reader.core.model.FacetGroup
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.opds.OpdsAcquisition
import net.dexxicon.reader.core.serverapi.opds.OpdsClient
import net.dexxicon.reader.core.serverapi.opds.OpdsFeed
import net.dexxicon.reader.core.serverapi.opds.OpdsFormatException
import net.dexxicon.reader.core.serverapi.opds.OpdsLink
import net.dexxicon.reader.core.serverapi.opds.OpdsPublication

/**
 * issue #291 — browsing an OPDS catalog (a generic server: Project Gutenberg, Open Library, or
 * any custom catalog; OPDS 2.0 with the 1.2 fallback, see [OpdsClient]). [Server.baseUrl] is
 * the catalog's root feed, and credentials, when there are any, go out as HTTP Basic through
 * the client's auth plugin.
 *
 * OPDS is navigation-driven, so it maps onto the Library like this:
 *  - the root feed's sections (its navigation links, and OPDS 2 groups with their own feed)
 *    are the server's **libraries** ([groups] with [BookGroupKind.LIBRARY]), picked from the
 *    Library's "Library:" menu;
 *  - a library's books are that feed's publications, paged by following its `next` links;
 *  - the whole catalog is the root's own publications, or its first section's when the root
 *    is navigation-only (Gutenberg's root lists "Popular", "Latest", …);
 *  - search uses the catalog's OPDS 2 template or OpenSearch description.
 *
 * A book's id is its detail URL (its own feed/document) when it has one; otherwise (common in
 * OPDS 1 catalogs such as Calibre's) it encodes the feed it was listed in plus its entry id, so
 * any instance can refetch that feed and find it — the reader resolves books through its own
 * graph's instance, and ids must survive a restart ([entryBookId]).
 *
 * Sorting and filtering (issue #293) go through the catalog's own facets: each page reports them
 * ([BookPage.facets]) and a chosen one comes back as `facetHref`, the list to show instead. The
 * app's [BookSort] and format filter can't be expressed in OPDS, so [sort] and [formats] are
 * ignored here ([BookPage.appSortApplies] is false).
 *
 * Scaffolding (issue #291): only open-access downloads are offered, in formats the app reads
 * itself (EPUB, PDF, comics). Borrowing (e.g. Open Library's Internet Archive loans) is left
 * for later.
 */
class OpdsCatalogSource(private val client: OpdsClient) : CatalogSource {

    private val lock = Mutex()
    /** Per catalog: the start of a paged list → the URLs of its pages found so far. */
    private val pageUrls = mutableMapOf<String, MutableList<String?>>()
    /** Books listed this session, by id — for entries with no detail URL of their own. */
    private val recent = mutableMapOf<String, OpdsPublication>()

    override suspend fun shelves(server: Server): Outcome<List<CatalogShelf>> = call {
        sections(client.feed(server.baseUrl)).map { CatalogShelf(id = it.href, title = it.title) }
    }

    override suspend fun groups(server: Server, kind: BookGroupKind): Outcome<List<BookGroup>> {
        if (kind != BookGroupKind.LIBRARY) return Outcome.Success(emptyList())
        return call {
            sections(client.feed(server.baseUrl)).map { link ->
                BookGroup(
                    serverId = server.id,
                    kind = BookGroupKind.LIBRARY,
                    id = link.href,
                    name = link.title,
                    serverName = server.displayName,
                )
            }
        }
    }

    override suspend fun books(
        server: Server,
        shelfId: String?,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
        formats: Set<ContentFormat>?,
        facetHref: String?,
    ): Outcome<BookPage> = call {
        val start = when {
            // issue #293 — a chosen facet is its own list (it already carries any search).
            facetHref != null -> facetHref
            !query.isNullOrBlank() -> {
                val root = client.feed(server.baseUrl)
                val search = root.search ?: return@call BookPage(emptyList(), page, hasMore = false)
                client.searchUrl(search, query.trim()) ?: return@call BookPage(emptyList(), page, hasMore = false)
            }
            shelfId != null -> shelfId
            else -> server.baseUrl
        }
        val url = pageUrl(server, start, page) ?: return@call BookPage(emptyList(), page, hasMore = false)
        var feed = client.feed(url)
        // The whole catalog, when its root is navigation-only: show its first section instead.
        if (facetHref == null && shelfId == null && query.isNullOrBlank() && page == 0 && publicationsOf(feed).isEmpty()) {
            sections(feed).firstOrNull()?.let { first -> feed = client.feed(first.href) }
        }
        rememberNext(server, start, page, feed.nextUrl)
        val publications = publicationsOf(feed)
        lock.withLock { publications.forEach { recent[bookId(it, feed.url)] = it } }
        BookPage(
            books = publications.map { it.toSummary(server, bookId(it, feed.url)) },
            page = page,
            hasMore = feed.nextUrl != null,
            facets = feed.facets.map { g ->
                FacetGroup(g.title, g.facets.map { Facet(it.title, it.href, it.active, it.count) })
            },
            // A catalog sorts only through its own facets, if at all.
            appSortApplies = false,
        )
    }

    override suspend fun groupBooks(
        server: Server,
        group: BookGroup,
        query: String?,
        sort: BookSort,
        page: Int,
        pageSize: Int,
        formats: Set<ContentFormat>?,
        facetHref: String?,
    ): Outcome<BookPage> = books(server, group.id, query, sort, page, pageSize, formats, facetHref)

    override suspend fun detail(server: Server, bookId: String): Outcome<BookDetail> = call {
        val listed = lock.withLock { recent[bookId] }
        val full = when {
            bookId.startsWith(ENTRY_PREFIX) -> {
                val (feedUrl, entryId) = decodeEntryBookId(bookId)
                listed ?: publicationsOf(client.feed(feedUrl)).firstOrNull { it.id == entryId }
            }
            bookId.startsWith("http") -> client.publication(bookId)
            else -> listed
        } ?: throw OpdsFormatException("That book isn't in the catalog any more")
        // A summary's title/cover can be better than the detail document's (Gutenberg's derived
        // cover); keep whichever each field has.
        val merged = listed?.let { l ->
            full.copy(coverUrl = full.coverUrl ?: l.coverUrl, thumbnailUrl = full.thumbnailUrl ?: l.thumbnailUrl)
        } ?: full
        val acquisitions = merged.acquisitions.mapNotNull { it.toAcquisition() }
        BookDetail(
            summary = merged.toSummary(server, bookId, acquisitions.minByOrNull { it.format.priority }?.format),
            description = merged.summary,
            publisher = merged.publisher,
            // issue #295 — Atom dates are full timestamps; the day is what's worth showing.
            publishedDate = merged.published?.let { DATE.find(it)?.value ?: it },
            language = merged.language,
            categories = merged.categories,
            acquisitions = acquisitions,
            fileExtension = acquisitions.minByOrNull { it.format.priority }?.format?.let(::extensionOf),
            fileSizeBytes = acquisitions.minByOrNull { it.format.priority }?.sizeBytes,
        )
    }

    // ---- helpers ------------------------------------------------------------------------

    /** The root's sections: its navigation, then OPDS 2 groups that have a feed of their own. */
    private fun sections(root: OpdsFeed): List<OpdsLink> =
        (root.navigation + root.groups.mapNotNull { g -> g.href?.let { OpdsLink(g.title, it) } })
            .distinctBy { it.href }

    /** A feed's books: its own publications, else its groups' (Open Library's root). */
    private fun publicationsOf(feed: OpdsFeed): List<OpdsPublication> =
        feed.publications.ifEmpty { feed.groups.flatMap { it.publications } }.distinctBy { bookId(it, feed.url) }

    /** The URL of [page] of the list starting at [start], if the pages before it said where. */
    private suspend fun pageUrl(server: Server, start: String, page: Int): String? = lock.withLock {
        if (page == 0) return@withLock start
        pageUrls["${server.id}|$start"]?.getOrNull(page)
    }

    private suspend fun rememberNext(server: Server, start: String, page: Int, next: String?) = lock.withLock {
        val pages = pageUrls.getOrPut("${server.id}|$start") { mutableListOf(start) }
        while (pages.size <= page + 1) pages.add(null)
        pages[page + 1] = next
    }

    private fun bookId(p: OpdsPublication, listedIn: String): String = p.detailUrl ?: entryBookId(listedIn, p.id)

    private fun OpdsPublication.toSummary(server: Server, id: String, format: ContentFormat? = null) = BookSummary(
        id = id,
        serverId = server.id,
        title = title,
        authors = authors,
        series = series,
        seriesIndex = seriesPosition,
        coverUrl = thumbnailUrl ?: coverUrl,
        format = format ?: acquisitions.mapNotNull { it.toAcquisition()?.format }.minByOrNull { it.priority }
            ?: ContentFormat.UNKNOWN,
    )

    /** Only open-access files in a format the app reads itself (scaffolding, issue #291). */
    private fun OpdsAcquisition.toAcquisition(): Acquisition? {
        if (!isOpenAccess) return null
        val format = ContentFormat.fromMediaType(type)
        if (format !in READABLE) return null
        return Acquisition(
            href = href,
            mediaType = type.orEmpty(),
            format = format,
            relation = AcquisitionRelation.OPEN_ACCESS,
            sizeBytes = length,
        )
    }

    private fun extensionOf(format: ContentFormat): String? = when (format) {
        ContentFormat.EPUB -> "epub"
        ContentFormat.PDF -> "pdf"
        ContentFormat.COMIC -> "cbz"
        else -> null
    }

    private suspend inline fun <T> call(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (e: ResponseException) {
        when (val status = e.response.status.value) {
            401, 403 -> Outcome.Failure(DexxiconError.Unauthorized("The catalog didn't accept these credentials"))
            404 -> Outcome.Failure(DexxiconError.NotFound("That's no longer in the catalog"))
            else -> Outcome.Failure(DexxiconError.Network("The catalog returned HTTP $status"))
        }
    } catch (e: OpdsFormatException) {
        Outcome.Failure(DexxiconError.Parse(e.message))
    } catch (e: IOException) {
        Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
    } catch (e: Exception) {
        Outcome.Failure(DexxiconError.Unknown(e.message, e))
    }

    internal companion object {
        val READABLE = setOf(ContentFormat.EPUB, ContentFormat.PDF, ContentFormat.COMIC)
        const val ENTRY_PREFIX = "opds-entry:"
        val DATE = Regex("""^\d{4}-\d{2}-\d{2}""")

        /** An id for a book with no detail URL: the feed it was listed in + its entry id. */
        @OptIn(ExperimentalEncodingApi::class)
        fun entryBookId(feedUrl: String, entryId: String): String =
            ENTRY_PREFIX + Base64.UrlSafe.encode(feedUrl.encodeToByteArray()) + ":" +
                Base64.UrlSafe.encode(entryId.encodeToByteArray())

        @OptIn(ExperimentalEncodingApi::class)
        fun decodeEntryBookId(bookId: String): Pair<String, String> {
            val (feed, entry) = bookId.removePrefix(ENTRY_PREFIX).split(':', limit = 2)
            return Base64.UrlSafe.decode(feed).decodeToString() to Base64.UrlSafe.decode(entry).decodeToString()
        }
    }
}
