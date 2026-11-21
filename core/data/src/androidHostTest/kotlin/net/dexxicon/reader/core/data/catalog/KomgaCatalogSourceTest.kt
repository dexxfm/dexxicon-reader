package net.dexxicon.reader.core.data.catalog

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.AcquisitionRelation
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.KomgaBrowseApi
import org.junit.Test

/** issue #327 — Komga's REST API, mocked with the shapes the test server returns. */
class KomgaCatalogSourceTest {

    private val server = Server(
        id = "k",
        displayName = "Komga",
        baseUrl = "https://komga.test",
        type = ServerType.KOMGA,
        authMode = AuthMode.BASIC,
    )
    private val requests = mutableListOf<HttpRequestData>()

    private fun source(routes: Map<String, Pair<HttpStatusCode, String>>): KomgaCatalogSource {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = routes.entries.firstOrNull { request.url.encodedPath == it.key }?.value
                ?: (HttpStatusCode.NotFound to """{"status":404}""")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }
        return KomgaCatalogSource(KomgaBrowseApi(client))
    }

    private val epub = """
        {"id":"b1","seriesId":"s1","seriesTitle":"Hardwired","libraryId":"lib1","name":"Hardwired",
         "url":"Hardwired.epub","sizeBytes":2370810,
         "media":{"status":"READY","mediaType":"application/epub+zip","pagesCount":339,"mediaProfile":"EPUB"},
         "metadata":{"title":"Hardwired: 30th Anniversary","summary":"Cyberpunk.","numberSort":1.0,
           "releaseDate":"2011-01-01","authors":[{"name":"Walter Jon Williams","role":"writer"},
           {"name":"Someone","role":"cover"}],"tags":["sf"],"isbn":""},
         "readProgress":{"page":339,"completed":true,"readDate":"2026-11-21T17:00:00Z"}}
    """.trimIndent()
    private val comic = """
        {"id":"b2","seriesId":"s2","seriesTitle":"GIGANT","libraryId":"lib2","name":"GIGANT Vol. 2",
         "url":"GIGANT 02.cbz","media":{"mediaType":"application/zip","pagesCount":214,"mediaProfile":"DIVINA"},
         "metadata":{"title":"GIGANT Vol. 2","numberSort":2.0,"authors":[{"name":"Hiroya Oku","role":"writer"}]}}
    """.trimIndent()

    private fun page(vararg books: String, last: Boolean = true) =
        """{"content":[${books.joinToString(",")}],"number":0,"size":20,"totalElements":${books.size},"totalPages":1,"last":$last}"""

    @Test
    fun aLibraryPageFiltersOnTheServerAndMapsEachFile() = runTest {
        val source = source(mapOf("/api/v1/books/list" to (HttpStatusCode.OK to page(epub, comic, last = false))))

        val result = source.books(
            server, shelfId = "lib1", query = "hard", sort = BookSort.TITLE, page = 2, pageSize = 20,
            formats = setOf(ContentFormat.EPUB, ContentFormat.PDF),
        )

        val books = (result as Outcome.Success).value
        assertThat(books.hasMore).isTrue()
        assertThat(books.books.map { it.format }).containsExactly(ContentFormat.EPUB, ContentFormat.COMIC).inOrder()
        val first = books.books.first()
        assertThat(first.title).isEqualTo("Hardwired: 30th Anniversary")
        assertThat(first.authors).containsExactly("Walter Jon Williams") // writers only
        assertThat(first.coverUrl).isEqualTo("https://komga.test/api/v1/books/b1/thumbnail")
        assertThat(first.seriesIndex).isEqualTo(1.0)

        val request = requests.single()
        assertThat(request.url.parameters["page"]).isEqualTo("2")
        assertThat(request.url.parameters.getAll("sort")).containsExactly("metadata.title,asc")
        val body = (request.body as TextContent).text
        assertThat(body).contains(""""libraryId":{"operator":"is","value":"lib1"}""")
        assertThat(body).contains(""""mediaProfile":{"operator":"is","value":"EPUB"}""")
        assertThat(body).contains(""""mediaProfile":{"operator":"is","value":"PDF"}""")
        assertThat(body).contains(""""fullTextSearch":"hard"""")
    }

    @Test
    fun aBookDetailDownloadsTheWholeFileAndKnowsItsReadingStatus() = runTest {
        val source = source(
            mapOf(
                "/api/v1/books/b1" to (HttpStatusCode.OK to epub),
                "/api/v1/series/s1" to (HttpStatusCode.OK to
                    """{"id":"s1","name":"Hardwired","metadata":{"publisher":"Night Shade","language":"en","genres":["Cyberpunk"]}}"""),
            ),
        )

        val detail = (source.detail(server, "b1") as Outcome.Success).value

        val file = detail.acquisitions.single()
        assertThat(file.href).isEqualTo("https://komga.test/api/v1/books/b1/file")
        assertThat(file.format).isEqualTo(ContentFormat.EPUB)
        assertThat(file.relation).isEqualTo(AcquisitionRelation.OPEN_ACCESS) // Komga ignores ranges
        assertThat(detail.readingStatus).isEqualTo(ReadingStatus.READ)
        assertThat(detail.publisher).isEqualTo("Night Shade")
        assertThat(detail.categories).containsExactly("Cyberpunk", "sf")
        assertThat(detail.fileExtension).isEqualTo("epub")
        assertThat(detail.pageCount).isNull() // an EPUB's Komga pages aren't print pages
    }

    @Test
    fun aSeriesReadsInItsOwnOrder() = runTest {
        val source = source(mapOf("/api/v1/books/list" to (HttpStatusCode.OK to page(comic))))
        val series = BookGroup(server.id, BookGroupKind.SERIES, "s2", "GIGANT")

        source.groupBooks(server, series, query = null, sort = BookSort.RECENT, page = 0, pageSize = 50)

        val request = requests.single()
        assertThat(request.url.parameters.getAll("sort")).containsExactly("metadata.numberSort,asc", "metadata.title,asc").inOrder()
        assertThat((request.body as TextContent).text).contains(""""seriesId":{"operator":"is","value":"s2"}""")
    }

    @Test
    fun aMissingBookIsNotFoundNotAnOutage() = runTest {
        val result = source(emptyMap()).detail(server, "gone")
        assertThat((result as Outcome.Failure).error).isInstanceOf(DexxiconError.NotFound::class.java)
    }
}
