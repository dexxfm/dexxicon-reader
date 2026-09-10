package net.dexxicon.reader.core.data.catalog

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.NullableBodyConverterFactory
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * BookOrbit's `POST /api/v1/books/query` validates `sort[].field` against its `SortField`
 * union and answers **HTTP 400** for anything else. "Sort by series" must therefore send
 * `series` (+ `seriesIndex`), never `seriesName` — that mistake shipped a broken series sort.
 */
class BookOrbitCatalogSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: BookOrbitCatalogSource
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(
                NullableBodyConverterFactory(json.asConverterFactory("application/json".toMediaType())),
            )
            .build()
            .create(BookOrbitBrowseApi::class.java)
        source = BookOrbitCatalogSource(api)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun testServer() = Server(
        id = "bo",
        displayName = "BookOrbit",
        baseUrl = server.url("/").toString().trimEnd('/'),
        type = ServerType.BOOKORBIT,
    )

    private fun sortFieldsFor(sort: BookSort): List<String> = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0,"page":0,"size":50}"""))
        val outcome = source.books(testServer(), shelfId = null, query = null, sort = sort, page = 0, pageSize = 50)
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject.getValue("sort").jsonArray
            .map { it.jsonObject.getValue("field").jsonPrimitive.content }
    }

    @Test
    fun `series sort sends the fields BookOrbit accepts, not seriesName`() {
        val fields = sortFieldsFor(BookSort.SERIES)
        assertThat(fields).containsExactly("series", "seriesIndex").inOrder()
        assertThat(fields).doesNotContain("seriesName")
    }

    @Test
    fun `recent and title sorts send their own valid fields`() {
        assertThat(sortFieldsFor(BookSort.RECENT)).containsExactly("addedAt")
        assertThat(sortFieldsFor(BookSort.TITLE)).containsExactly("title")
    }
}
