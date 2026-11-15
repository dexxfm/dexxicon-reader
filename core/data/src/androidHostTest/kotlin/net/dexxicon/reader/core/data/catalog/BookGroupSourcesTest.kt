package net.dexxicon.reader.core.data.catalog

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import org.junit.Test

/**
 * Issues #253/#254/#256 — each server family's library / collection / smart shelf / series
 * endpoints: the right path and parameters go out (both servers answer a wrong one with a
 * 400 or an unfiltered list rather than an error), and what comes back maps onto [BookGroup]s
 * and books correctly. Paths are from each server's own source (BookOrbit's Nest controllers,
 * Grimmory's supported web API — issue #335).
 */
class BookGroupSourcesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val requests = mutableListOf<Pair<HttpMethod, Url>>()
    private val bodies = mutableListOf<String>()

    private fun client(response: String): HttpClient {
        val engine = MockEngine { request ->
            requests += request.method to request.url
            bodies += request.body.toByteArray().decodeToString()
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    }

    private val bookOrbit = Server("bo", "Orbit", "https://orbit.test", type = ServerType.BOOKORBIT)
    private val grimmory = Server("gr", "Grim", "https://grim.test", type = ServerType.GRIMMORY)

    private fun <T> Outcome<T>.value(): T = (this as Outcome.Success).value

    // --- BookOrbit -------------------------------------------------------------------------

    @Test
    fun `BookOrbit collections keep book collections only`() = runBlocking<Unit> {
        val source = BookOrbitCatalogSource(
            BookOrbitBrowseApi(
                client(
                    """[{"id":1,"name":"Favourites","mediaType":"books","bookCount":3},
                        {"id":2,"name":"Shows","mediaType":"podcasts","bookCount":0}]""",
                ),
            ),
        )
        val groups = source.groups(bookOrbit, BookGroupKind.COLLECTION).value()

        assertThat(requests.single().second.encodedPath).isEqualTo("/api/v1/collections")
        assertThat(groups.map { it.name }).containsExactly("Favourites")
        assertThat(groups.single().key).isEqualTo("bo:COLLECTION:1")
        assertThat(groups.single().bookCount).isEqualTo(3)
    }

    @Test
    fun `BookOrbit series list searches by name and maps the first cover book`() = runBlocking<Unit> {
        val source = BookOrbitCatalogSource(
            BookOrbitBrowseApi(
                client(
                    """{"items":[{"id":7,"name":"Mistborn","bookCount":3,"authors":["B. S."],"coverBookIds":[41,42]}],
                        "total":120,"page":0,"size":40}""",
                ),
            ),
        )
        val page = source.series(bookOrbit, "mist", 0, 40).value()

        val url = requests.single().second
        assertThat(url.encodedPath).isEqualTo("/api/v1/series")
        assertThat(url.parameters["q"]).isEqualTo("mist")
        assertThat(url.parameters["sort"]).isEqualTo("name")
        assertThat(page.hasMore).isTrue()
        val group = page.groups.single()
        assertThat(group.id).isEqualTo("7")
        assertThat(group.coverUrl).isEqualTo("https://orbit.test/api/v1/books/41/cover")
        assertThat(group.authors).containsExactly("B. S.")
    }

    @Test
    fun `BookOrbit series books come from the series endpoint in series order`() = runBlocking<Unit> {
        val source = BookOrbitCatalogSource(
            BookOrbitBrowseApi(
                client(
                    """{"items":[{"id":41,"title":"The Final Empire","seriesName":"Mistborn","seriesIndex":"1","seriesId":7}],
                        "total":1,"page":0,"size":40,"seriesInfo":{"id":7,"name":"Mistborn"}}""",
                ),
            ),
        )
        val group = BookGroup("bo", BookGroupKind.SERIES, "7", "Mistborn")
        val page = source.groupBooks(bookOrbit, group, null, BookSort.RECENT, 0, 40).value()

        val (method, url) = requests.single()
        assertThat(method).isEqualTo(HttpMethod.Get)
        assertThat(url.encodedPath).isEqualTo("/api/v1/series/7/books")
        assertThat(url.parameters["sort"]).isEqualTo("seriesIndex")
        assertThat(page.books.single().seriesId).isEqualTo("7")
        assertThat(page.books.single().seriesIndex).isEqualTo(1.0)
    }

    @Test
    fun `BookOrbit collection and smart-scope books go through their query endpoints`() = runBlocking<Unit> {
        val source = BookOrbitCatalogSource(BookOrbitBrowseApi(client("""{"items":[],"total":0,"page":0,"size":40}""")))
        source.groupBooks(bookOrbit, BookGroup("bo", BookGroupKind.COLLECTION, "3", "C"), "dune", BookSort.TITLE, 0, 40)
        source.groupBooks(bookOrbit, BookGroup("bo", BookGroupKind.SMART, "9", "S"), null, BookSort.TITLE, 0, 40)
        source.groupBooks(bookOrbit, BookGroup("bo", BookGroupKind.LIBRARY, "2", "L"), null, BookSort.TITLE, 0, 40)

        assertThat(requests.map { it.first }).containsExactly(HttpMethod.Post, HttpMethod.Post, HttpMethod.Post)
        assertThat(requests.map { it.second.encodedPath }).containsExactly(
            "/api/v1/collections/3/books/query",
            "/api/v1/smart-scopes/9/books/query",
            "/api/v1/libraries/2/books",
        ).inOrder()
        assertThat(bodies.first()).contains("\"q\":\"dune\"")
    }

    // --- Grimmory (issue #335: its supported web API) ---------------------------------------

    /**
     * Answers by path: [routes] maps an encoded path to a JSON body. Any other path gets what
     * Grimmory 3.5.0 really answers: its web app's `index.html`, with a 200.
     */
    private fun routed(routes: Map<String, String>): HttpClient {
        val engine = MockEngine { request ->
            requests += request.method to request.url
            val body = routes[request.url.encodedPath]
                ?: return@MockEngine respond(
                    "<!doctype html><html><head><title>Grimmory</title></head></html>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
        }
    }

    /** One `/books/page` row. */
    private fun bookJson(id: Long, title: String, type: String, series: String? = null) =
        """{"id":$id,"metadata":{"title":"$title","authors":["A. Author"]${series?.let { ""","seriesName":"$it","seriesNumber":1.0""" } ?: ""}},
            "primaryFile":{"id":${id * 10},"bookType":"$type"}}"""

    private fun pageJson(vararg books: String, totalElements: Int = books.size, totalPages: Int = 1) =
        """{"content":[${books.joinToString(",")}],"page":{"number":0,"size":40,"totalElements":$totalElements,"totalPages":$totalPages}}"""

    @Test
    fun `Grimmory library, shelf, magic shelf and series books are facets of books page`() = runBlocking<Unit> {
        val source = GrimmoryCatalogSource(
            GrimmoryBrowseApi(routed(mapOf("/api/v1/books/page" to pageJson(bookJson(5, "Dune", "AUDIOBOOK"))))),
        )
        val page = source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.LIBRARY, "4", "L"), "dune", BookSort.TITLE, 0, 40).value()
        source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.COLLECTION, "6", "S"), null, BookSort.RECENT, 0, 40)
        source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.SMART, "8", "M"), null, BookSort.RECENT, 0, 500)
        source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.SERIES, "The Expanse", "The Expanse"), null, BookSort.RECENT, 0, 40)

        val (lib, shelf, magic, series) = requests.map { it.second }
        listOf(lib, shelf, magic, series).forEach { assertThat(it.encodedPath).isEqualTo("/api/v1/books/page") }
        assertThat(lib.parameters.getAll("facet")).containsExactly("library:4")
        assertThat(lib.parameters["query"]).isEqualTo("dune")
        assertThat(lib.parameters["sort"]).isEqualTo("title")
        assertThat(shelf.parameters.getAll("facet")).containsExactly("shelf:6")
        assertThat(shelf.parameters["sort"]).isEqualTo("-addedOn")
        assertThat(magic.parameters.getAll("facet")).containsExactly("shelf:magic:8")
        assertThat(magic.parameters["size"]).isEqualTo("100") // Grimmory's page-size limit
        assertThat(series.parameters.getAll("facet")).containsExactly("series:The Expanse")
        assertThat(series.parameters["sort"]).isEqualTo("seriesNumber")

        val book = page.books.single()
        assertThat(book.format).isEqualTo(ContentFormat.AUDIOBOOK)
        assertThat(book.coverUrl).isEqualTo("https://grim.test/api/v1/media/book/5/audiobook-cover")
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `Grimmory libraries and magic shelves get their counts from books page`() = runBlocking<Unit> {
        val source = GrimmoryCatalogSource(
            GrimmoryBrowseApi(
                routed(
                    mapOf(
                        "/api/v1/libraries" to """[{"id":1,"name":"Books","paths":[]},{"id":2,"name":"Comics"}]""",
                        "/api/v1/shelves" to """[{"id":6,"name":"Favourites","bookCount":12,"publicShelf":false}]""",
                        "/api/magic-shelves" to """[{"id":8,"name":"Unread sci-fi","filterJson":"{}"}]""",
                        "/api/v1/books/page" to pageJson(totalElements = 42),
                    ),
                ),
            ),
        )
        val libraries = source.groups(grimmory, BookGroupKind.LIBRARY).value()
        val shelves = source.groups(grimmory, BookGroupKind.COLLECTION).value()
        val magic = source.groups(grimmory, BookGroupKind.SMART).value()

        assertThat(libraries.map { it.name to it.bookCount }).containsExactly("Books" to 42, "Comics" to 42).inOrder()
        assertThat(shelves.single().bookCount).isEqualTo(12)
        assertThat(magic.single().id).isEqualTo("8")
        assertThat(magic.single().bookCount).isEqualTo(42)
        val counted = requests.map { it.second }.filter { it.encodedPath == "/api/v1/books/page" }
        assertThat(counted.flatMap { it.parameters.getAll("facet").orEmpty() })
            .containsExactly("library:1", "library:2", "shelf:magic:8")
        assertThat(counted.map { it.parameters["size"] }.distinct()).containsExactly("1")
        // Grimmory rejects `sort=id` (reserved for its tiebreaker) with a 400.
        assertThat(counted.map { it.parameters["sort"] }.distinct()).containsExactly("addedOn")
        // Never the deprecated app API (grimmory#2812).
        assertThat(requests.none { it.second.encodedPath.startsWith("/api/v1/app/") }).isTrue()
    }

    @Test
    fun `Grimmory series come from the paged series facet where the server has it`() = runBlocking<Unit> {
        val source = GrimmoryCatalogSource(
            GrimmoryBrowseApi(
                routed(
                    mapOf(
                        "/api/v1/books/facets/series" to """{"links":[{"rel":"self","href":"/x"},{"rel":"next","href":"/y"}],
                            "facets":[{"metadata":{"rel":"facet","key":"series","title":"Series"},
                            "links":[{"rel":"facet","title":"The Expanse","value":"The Expanse","properties":{"numberOfItems":9}}]}]}""",
                        "/api/v1/books/page" to pageJson(bookJson(12, "Leviathan Wakes", "EPUB", "The Expanse")),
                    ),
                ),
            ),
        )
        val page = source.series(grimmory, "exp", 0, 40).value()
        val group = page.groups.single()
        assertThat(group.id).isEqualTo("The Expanse")
        assertThat(group.bookCount).isEqualTo(9)
        assertThat(group.coverUrl).isEqualTo("https://grim.test/api/v1/media/book/12/cover")
        assertThat(group.authors).containsExactly("A. Author")
        assertThat(page.hasMore).isTrue()
        val facetCall = requests.first().second
        assertThat(facetCall.parameters["search"]).isEqualTo("exp")
        assertThat(facetCall.parameters["page"]).isEqualTo("0")
    }

    @Test
    fun `Grimmory 3_5 series fall back to the all-facets response, filtered by name`() = runBlocking<Unit> {
        val source = GrimmoryCatalogSource(
            GrimmoryBrowseApi(
                routed(
                    mapOf(
                        // No /books/facets/series on 3.5.0 — routed() answers it with index.html.
                        "/api/v1/books/facets" to """{"facets":[
                            {"metadata":{"key":"file_type"},"links":[{"value":"EPUB","properties":{"numberOfItems":3}}]},
                            {"metadata":{"key":"series"},"links":[
                              {"value":"The Expanse","properties":{"numberOfItems":9}},
                              {"value":"Dune","properties":{"numberOfItems":6}}]}]}""",
                        "/api/v1/books/page" to pageJson(bookJson(12, "Leviathan Wakes", "EPUB", "The Expanse")),
                    ),
                ),
            ),
        )
        val all = source.series(grimmory, null, 0, 40).value()
        assertThat(all.groups.map { it.id }).containsExactly("The Expanse", "Dune").inOrder()
        assertThat(all.hasMore).isFalse()

        val searched = source.series(grimmory, "dun", 0, 40).value()
        assertThat(searched.groups.map { it.id }).containsExactly("Dune")
        // The 3.5.0 response has no paging, so there is no page 2.
        assertThat(source.series(grimmory, null, 1, 40).value().groups).isEmpty()

        // And the Library's format chips still read file_type from it.
        val chips = source.shelves(grimmory).value()
        assertThat(chips.single().id).isEqualTo("EPUB")
        assertThat(chips.single().bookCount).isEqualTo(3)
    }
}
