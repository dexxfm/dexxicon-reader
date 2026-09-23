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
 * Grimmory's `app/controller`).
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

    // --- Grimmory --------------------------------------------------------------------------

    @Test
    fun `Grimmory library, shelf and magic shelf books filter the app books endpoint`() = runBlocking<Unit> {
        val source = GrimmoryCatalogSource(
            GrimmoryBrowseApi(
                client(
                    """{"content":[{"id":5,"title":"Dune","authors":["F. H."],"seriesName":"Dune","seriesNumber":1.0,
                        "primaryFileType":"AUDIOBOOK"}],"page":0,"size":40,"totalElements":1,"totalPages":1,"hasNext":false}""",
                ),
            ),
        )
        val page = source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.LIBRARY, "4", "L"), "dune", BookSort.TITLE, 0, 40).value()
        source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.COLLECTION, "6", "S"), null, BookSort.RECENT, 0, 40)
        source.groupBooks(grimmory, BookGroup("gr", BookGroupKind.SMART, "8", "M"), null, BookSort.RECENT, 0, 40)

        val (lib, shelf, magic) = requests.map { it.second }
        assertThat(lib.encodedPath).isEqualTo("/api/v1/app/books")
        assertThat(lib.parameters["libraryId"]).isEqualTo("4")
        assertThat(lib.parameters["search"]).isEqualTo("dune")
        assertThat(lib.parameters["sort"]).isEqualTo("title")
        assertThat(shelf.parameters["shelfId"]).isEqualTo("6")
        assertThat(magic.parameters["magicShelfId"]).isEqualTo("8")
        assertThat(magic.parameters["sort"]).isEqualTo("addedOn")

        val book = page.books.single()
        assertThat(book.format).isEqualTo(ContentFormat.AUDIOBOOK)
        assertThat(book.coverUrl).isEqualTo("https://grim.test/api/v1/media/book/5/audiobook-cover")
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `Grimmory series are keyed by name, path-encoded for their books`() = runBlocking<Unit> {
        val source = GrimmoryCatalogSource(
            GrimmoryBrowseApi(
                client(
                    """{"content":[{"seriesName":"The Expanse","bookCount":9,"authors":["J. S. A. Corey"],
                        "coverBooks":[{"bookId":12,"primaryFileType":"EPUB"}]}],"page":0,"size":40,"totalPages":3,"hasNext":true}""",
                ),
            ),
        )
        val page = source.series(grimmory, null, 0, 40).value()
        val group = page.groups.single()
        assertThat(group.id).isEqualTo("The Expanse")
        assertThat(group.coverUrl).isEqualTo("https://grim.test/api/v1/media/book/12/cover")
        assertThat(page.hasMore).isTrue()

        source.groupBooks(grimmory, group, null, BookSort.RECENT, 0, 40)
        val url = requests.last().second
        assertThat(url.encodedPath).isEqualTo("/api/v1/app/series/The%20Expanse/books")
        assertThat(url.parameters["sort"]).isEqualTo("seriesNumber")
        assertThat(url.parameters["dir"]).isEqualTo("asc")
    }
}
