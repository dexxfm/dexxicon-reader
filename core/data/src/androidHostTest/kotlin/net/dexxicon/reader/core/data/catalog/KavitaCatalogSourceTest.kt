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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import net.dexxicon.reader.core.serverapi.browse.KavitaBrowseApi
import org.junit.Test

/** issue #328 — Kavita's REST API, mocked with the shapes the Kavita 0.9.1 test server returns. */
class KavitaCatalogSourceTest {

    private val server = Server(
        id = "kv",
        displayName = "Kavita",
        baseUrl = "https://kavita.test",
        type = ServerType.KAVITA,
        authMode = AuthMode.NATIVE,
    )
    private val requests = mutableListOf<HttpRequestData>()

    /** Routes on the path plus, when present, the `seriesId` / `chapterId` query parameter. */
    private fun source(routes: Map<String, String>, status: HttpStatusCode = HttpStatusCode.OK, pagination: String? = null): KavitaCatalogSource {
        val engine = MockEngine { request ->
            requests += request
            val id = request.url.parameters["seriesId"] ?: request.url.parameters["chapterId"]
            val key = request.url.encodedPath + (id?.let { "?$it" } ?: "")
            val body = routes[key] ?: routes[request.url.encodedPath]
            val headers = buildList {
                add(HttpHeaders.ContentType to listOf("application/json"))
                if (pagination != null && request.url.encodedPath == "/api/Series/all-v2") add("Pagination" to listOf(pagination))
            }
            if (body == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(body, status, headersOf(*headers.toTypedArray()))
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }
        return KavitaCatalogSource(KavitaBrowseApi(client))
    }

    private val libraries = """[{"id":4,"name":"Books","type":2},{"id":3,"name":"Manga","type":0},{"id":5,"name":"Comics","type":1}]"""

    private val murderbot = """{"id":351,"name":"All Systems Red","format":3,"pages":18,"libraryId":4,"libraryName":"Books"}"""
    private val inuyashiki = """{"id":428,"name":"Inuyashiki","format":1,"pages":400,"libraryId":3,"libraryName":"Manga"}"""
    private val robotech = """{"id":287,"name":"Robotech","format":1,"pages":300,"libraryId":5,"libraryName":"Comics"}"""

    private fun chapter(
        id: Int, volumeId: Int, number: String = "-100000", range: String = number, title: String = number,
        special: Boolean = false, format: Int = 1, ext: String = ".cbz", pages: Int = 200, read: Int = 0, min: Double = -100000.0,
        sort: Double = 0.0, extra: String = "",
    ) = """{"id":$id,"volumeId":$volumeId,"number":"$number","range":"$range","title":"$title","titleName":"",
        "minNumber":$min,"sortOrder":$sort,"isSpecial":$special,"pages":$pages,"pagesRead":$read,"format":$format,
        "files":[{"id":${id + 5000},"filePath":"/x$ext","pages":$pages,"bytes":1000,"format":$format,"extension":"$ext"}]$extra}"""

    private fun volume(id: Int, name: String, min: Double, vararg chapters: String) =
        """{"id":$id,"name":"$name","minNumber":$min,"seriesId":0,"chapters":[${chapters.joinToString(",")}]}"""

    private val murderbotVolumes = "[" + volume(
        485, "-100000", -100000.0,
        chapter(
            826, 485, title = "All Systems Red", special = true, format = 3, ext = ".epub", pages = 18, read = 5,
            extra = ""","writers":[{"name":"Martha Wells"}],"genres":[{"title":"Science Fiction"}]""",
        ),
    ) + "]"
    private val inuyashikiVolumes = "[" + listOf(
        volume(661, "1", 1.0, chapter(946, 661)),
        volume(662, "2", 2.0, chapter(948, 662)),
    ).joinToString(",") + "]"

    @Test
    fun aLibraryListsLoneBooksAsBooksAndLongerSeriesAsCards() = runTest {
        val source = source(
            mapOf(
                "/api/Library/libraries" to libraries,
                "/api/Series/all-v2" to "[$murderbot,$inuyashiki]",
                "/api/Series/volumes?351" to murderbotVolumes,
                "/api/Series/volumes?428" to inuyashikiVolumes,
            ),
            pagination = """{"currentPage":3,"itemsPerPage":20,"totalItems":76,"totalPages":4}""",
        )

        val result = source.books(
            server, shelfId = "4", query = "red", sort = BookSort.TITLE, page = 2, pageSize = 20,
            formats = setOf(ContentFormat.EPUB, ContentFormat.PDF),
        )

        val page = (result as Outcome.Success).value
        assertThat(page.hasMore).isTrue()
        assertThat(page.total).isEqualTo(76)
        val (lone, series) = page.books
        assertThat(lone.id).isEqualTo("826")
        assertThat(lone.title).isEqualTo("All Systems Red")
        assertThat(lone.authors).containsExactly("Martha Wells")
        assertThat(lone.format).isEqualTo(ContentFormat.EPUB)
        assertThat(lone.opensGroup).isNull()
        assertThat(lone.coverUrl).isEqualTo("https://kavita.test/api/Image/series-cover?seriesId=351")
        assertThat(series.id).isEqualTo("s:428")
        assertThat(series.format).isEqualTo(ContentFormat.COMIC)
        assertThat(series.opensGroup?.kind).isEqualTo(BookGroupKind.SERIES)
        assertThat(series.opensGroup?.id).isEqualTo("428")
        assertThat(series.opensGroup?.bookCount).isEqualTo(2)

        // Kavita pages from 1, and every statement must hold (combination 1).
        val list = requests.first { it.url.encodedPath == "/api/Series/all-v2" }
        assertThat(list.url.parameters["PageNumber"]).isEqualTo("3")
        assertThat(list.url.parameters["PageSize"]).isEqualTo("20")
        val body = Json.parseToJsonElement((list.body as TextContent).text).jsonObject
        assertThat(body["combination"]?.jsonPrimitive?.int).isEqualTo(1)
        assertThat(body["sortOptions"]?.jsonObject?.get("sortField")?.jsonPrimitive?.int).isEqualTo(1)
        val statements = body["statements"]!!.jsonArray.map { s ->
            Triple(s.jsonObject["field"]!!.jsonPrimitive.int, s.jsonObject["comparison"]!!.jsonPrimitive.int, s.jsonObject["value"]!!.jsonPrimitive.content)
        }
        assertThat(statements).containsExactly(Triple(19, 0, "4"), Triple(1, 7, "red"), Triple(21, 5, "3,4"))
    }

    @Test
    fun aSeriesListsEveryChapterInReadingOrderWithKavitasNames() = runTest {
        val volumes = "[" + listOf(
            volume(9, "100000", 100000.0, chapter(702, 9, title = "Robotech GN Vol 02", special = true)),
            volume(8, "-100000", -100000.0, chapter(692, 8, number = "3", min = 3.0, sort = 3.0), chapter(687, 8, number = "1", min = 1.0, sort = 1.0)),
            volume(7, "2", 2.0, chapter(720, 7)),
        ).joinToString(",") + "]"
        val source = source(
            mapOf(
                "/api/Library/libraries" to libraries,
                "/api/Series/287" to robotech,
                "/api/Series/volumes?287" to volumes,
            ),
        )

        val result = source.groupBooks(
            server, BookGroup(server.id, BookGroupKind.SERIES, "287", "Robotech"),
            query = null, sort = BookSort.RECENT, page = 0, pageSize = 50,
        )

        val books = (result as Outcome.Success).value.books
        assertThat(books.map { it.id }).containsExactly("720", "687", "692", "702").inOrder()
        assertThat(books.map { it.title }).containsExactly("Robotech Vol. 2", "Robotech #1", "Robotech #3", "Robotech GN Vol 02").inOrder()
        assertThat(books.map { it.seriesIndex }).containsExactly(2.0, 1.0, 3.0, null).inOrder()
        assertThat(books.all { it.series == "Robotech" && it.opensGroup == null }).isTrue()
        assertThat(books[0].coverUrl).isEqualTo("https://kavita.test/api/Image/chapter-cover?chapterId=720")
    }

    @Test
    fun detailOffersTheChapterFileToFetchWhole() = runTest {
        val source = source(
            mapOf(
                "/api/Library/libraries" to libraries,
                "/api/Chapter?826" to chapter(
                    826, 485, title = "All Systems Red", special = true, format = 3, ext = ".epub", pages = 18, read = 5,
                    extra = ""","writers":[{"name":"Martha Wells"}],"genres":[{"title":"Science Fiction"}]""",
                ),
                "/api/Search/series-for-chapter?826" to murderbot,
                "/api/Series/volumes?351" to murderbotVolumes,
                "/api/Series/metadata?351" to """{"summary":"A murderous android.","language":"en","publishers":[{"name":"Tor"}]}""",
            ),
        )

        val detail = (source.detail(server, "826") as Outcome.Success).value

        assertThat(detail.summary.title).isEqualTo("All Systems Red")
        assertThat(detail.description).isEqualTo("A murderous android.")
        assertThat(detail.publisher).isEqualTo("Tor")
        assertThat(detail.language).isEqualTo("en")
        assertThat(detail.categories).containsExactly("Science Fiction")
        assertThat(detail.readingStatus).isEqualTo(ReadingStatus.READING)
        assertThat(detail.pageCount).isNull() // an EPUB's Kavita "pages" are its sections
        assertThat(detail.fileExtension).isEqualTo("epub")
        val file = detail.acquisitions.single()
        assertThat(file.href).isEqualTo("https://kavita.test/api/Download/chapter?chapterId=826")
        assertThat(file.relation).isEqualTo(AcquisitionRelation.OPEN_ACCESS)
        assertThat(file.mediaType).isEqualTo("application/epub+zip")
    }

    /** issue #349 — a chapter of several files is read page by page, with the image-only key. */
    @Test
    fun aChapterOfSeveralFilesIsReadPageByPage() = runTest {
        val twoFiles = """{"id":752,"volumeId":440,"number":"-100000","range":"-100000","title":"-100000","titleName":"",
            "minNumber":-100000,"sortOrder":0,"isSpecial":false,"pages":61,"pagesRead":0,"format":1,
            "files":[{"id":1,"filePath":"/a/Issue 001.cbr","pages":28,"bytes":56502901,"format":1,"extension":".cbr"},
                     {"id":2,"filePath":"/a/Issue 002.cbr","pages":33,"bytes":67850999,"format":1,"extension":".cbr"}]}"""
        val source = source(
            mapOf(
                "/api/Library/libraries" to libraries,
                "/api/Chapter?752" to twoFiles,
                "/api/Search/series-for-chapter?752" to """{"id":309,"name":"Wedding Special","format":1,"libraryId":5}""",
                "/api/Series/volumes?309" to "[" + volume(440, "1", 1.0, twoFiles) + "]",
                "/api/Series/metadata?309" to "{}",
                "/api/Account/refresh-account" to
                    """{"username":"test","apiKey":"account-key","authKeys":[{"name":"opds","key":"o"},{"name":"image-only","key":"img-key"}]}""",
            ),
        )

        val detail = (source.detail(server, "752") as Outcome.Success).value

        val stream = detail.pageStream!!
        assertThat(stream.count).isEqualTo(61)
        assertThat(stream.pageUrl(32))
            .isEqualTo("https://kavita.test/api/Reader/image?chapterId=752&apiKey=img-key&page=32")
        // A one-file chapter keeps its plain download.
        assertThat(
            (source(
                mapOf(
                    "/api/Library/libraries" to libraries,
                    "/api/Chapter?826" to chapter(826, 485, special = true, format = 3, ext = ".epub", pages = 18),
                    "/api/Search/series-for-chapter?826" to murderbot,
                    "/api/Series/volumes?351" to murderbotVolumes,
                ),
            ).detail(server, "826") as Outcome.Success).value.pageStream,
        ).isNull()
    }

    @Test
    fun aRefusedSignInIsReportedAsSuch() = runTest {
        val source = source(mapOf("/api/Library/libraries" to "\"\""), status = HttpStatusCode.Unauthorized)

        val result = source.shelves(server)

        assertThat((result as Outcome.Failure).error).isInstanceOf(DexxiconError.Unauthorized::class.java)
    }
}
