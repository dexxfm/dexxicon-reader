package net.dexxicon.reader.core.data.catalog

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import org.junit.Test

/**
 * BookOrbit's `POST /api/v1/books/query` validates `sort[].field` against its `SortField`
 * union and answers **HTTP 400** for anything else. "Sort by series" must therefore send
 * `series` (+ `seriesIndex`), never `seriesName` — that mistake shipped a broken series sort.
 */
class BookOrbitCatalogSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private lateinit var lastRequestBody: String

    private fun sourceFor(response: String): BookOrbitCatalogSource {
        val engine = MockEngine { request ->
            lastRequestBody = request.body.toByteArray().decodeToString()
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return BookOrbitCatalogSource(BookOrbitBrowseApi(client))
    }

    private fun testServer() = Server(
        id = "bo",
        displayName = "BookOrbit",
        baseUrl = "https://bookorbit.example.test",
        type = ServerType.BOOKORBIT,
    )

    private fun sortFieldsFor(sort: BookSort): List<String> = runBlocking {
        val source = sourceFor("""{"items":[],"total":0,"page":0,"size":50}""")
        val outcome = source.books(testServer(), shelfId = null, query = null, sort = sort, page = 0, pageSize = 50)
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        json.parseToJsonElement(lastRequestBody)
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
