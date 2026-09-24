package net.dexxicon.reader.core.serverapi.browse

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun mockApi(body: String): BookOrbitBrowseApi {
    val engine = MockEngine {
        respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
        }
    }
    return BookOrbitBrowseApi(client)
}

private const val CARD =
    """{"id":7,"title":"The Final Empire","authors":["Brandon Sanderson"],"seriesId":3,"seriesName":"Mistborn","seriesIndex":"1","readingProgress":45.0,"files":[{"id":101,"format":"epub","role":"primary","sizeBytes":1024}]}"""

/** issue #283 — the dashboard scrollers' two response shapes. */
class BookOrbitDashboardScrollerTest {

    @Test
    fun readsTheBookOrbit3ResponseObject() = runTest {
        val books = mockApi("""{"books":[$CARD],"total":12}""").dashboardScroller("https://example.test/x")
        assertEquals(1, books.size)
        assertEquals(7L, books.first().id)
        assertEquals("1", books.first().seriesIndex)
        assertEquals(45.0, books.first().readingProgress)
    }

    @Test
    fun stillReadsTheBareArrayOlderServersSend() = runTest {
        val books = mockApi("[$CARD]").dashboardScroller("https://example.test/x")
        assertEquals(listOf(7L), books.map { it.id })
    }

    @Test
    fun aNullTotalOrMissingBooksIsJustEmpty() = runTest {
        assertTrue(mockApi("""{"books":[],"total":null}""").dashboardScroller("https://example.test/x").isEmpty())
        assertTrue(mockApi("""{"total":0}""").dashboardScroller("https://example.test/x").isEmpty())
    }
}
