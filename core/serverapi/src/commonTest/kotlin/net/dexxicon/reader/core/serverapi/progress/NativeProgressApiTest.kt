package net.dexxicon.reader.core.serverapi.progress

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [NativeProgressApi] backed by a [MockEngine] answering every request with [body]. */
private fun mockApi(body: String, status: HttpStatusCode = HttpStatusCode.OK): NativeProgressApi {
    val engine = MockEngine {
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
        }
    }
    return NativeProgressApi(client)
}

/**
 * commonTest (not androidHostTest) — [NativeProgressApi] is commonMain now, so this runs on
 * both the Android host JVM (`testAndroidHostTest`) and the iOS simulator (Codemagic's
 * `ios-ci`, `iosSimulatorArm64Test`) via a [MockEngine] instead of MockWebServer (JVM-only).
 */
class NativeProgressApiTest {

    @Test
    fun bookOrbitAudioProgressTakesALiteralNullBody() = runTest {
        val response = mockApi("null").bookOrbitAudioProgress("https://example.test/x")
        assertTrue(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun bookOrbitAudioProgressParsesAPopulatedBody() = runTest {
        val dto = mockApi("""{"currentFileId":42,"positionSeconds":123.5,"percentage":12.3}""")
            .bookOrbitAudioProgress("https://example.test/x").body()
        assertEquals(42L, dto?.currentFileId)
        assertEquals(123.5, dto?.positionSeconds)
        assertEquals(12.3, dto?.percentage)
    }

    @Test
    fun bookOrbitPlaybackStateTakesALiteralNullBody() = runTest {
        val response = mockApi("null").bookOrbitPlaybackState("https://example.test/x")
        assertTrue(response.isSuccessful)
        assertNull(response.body())
    }

    @Test
    fun bookOrbitPlaybackStateParsesAPopulatedBody() = runTest {
        val dto = mockApi(
            """{"assetId":"aud_1","positionMs":123500,"percentage":12.3,"revision":3,"manifestRevision":"abc"}""",
        ).bookOrbitPlaybackState("https://example.test/x").body()
        assertEquals("aud_1", dto?.assetId)
        assertEquals(123500L, dto?.positionMs)
        assertEquals(12.3, dto?.percentage)
        assertEquals(3, dto?.revision)
        assertEquals("abc", dto?.manifestRevision)
    }

    // ---- Grimmory (issue #335: its supported web API, not the removed /api/v1/app API) ----

    @Test
    fun grimmoryProgressReadsTheBooksOwnProgressFields() = runTest {
        // GET /api/v1/books/{id}: progress rides on the Book DTO, 0-100.
        val dto = mockApi(
            """
            {
              "id": 767,
              "title": "Mind Hack",
              "readStatus": "READING",
              "lastReadTime": "2026-09-06T20:04:38Z",
              "primaryFile": {"id": 900, "bookType": "AUDIOBOOK"},
              "audiobookProgress": {"positionMs": 72302094, "trackIndex": 0, "percentage": 93.9},
              "epubProgress": {"cfi": "epubcfi(/6/4)", "href": "ch1.xhtml", "percentage": 12.5}
            }
            """.trimIndent(),
        ).grimmoryProgress("https://example.test/x").body()
        assertEquals(72302094L, dto?.audiobookProgress?.positionMs)
        assertEquals(93.9, dto?.audiobookProgress?.percentage)
        assertEquals(12.5, dto?.epubProgress?.percentage)
        assertEquals("2026-09-06T20:04:38Z", dto?.lastReadTime)
    }

    @Test
    fun grimmoryBookFilesPicksTheFileOfTheWantedTypeElseThePrimary() = runTest {
        val book = mockApi(
            """{"id":767,"primaryFile":{"id":900,"bookType":"EPUB"},
                "alternativeFormats":[{"id":901,"bookType":"AUDIOBOOK"},{"id":902,"bookType":"PDF"}]}""",
        ).grimmoryBookFiles("https://example.test/x")
        assertEquals(listOf(900L, 901L, 902L), book.files.map { it.id })
        assertEquals(901L, book.fileIdFor("AUDIOBOOK"))
        assertEquals(902L, book.fileIdFor("pdf"))
        assertEquals(900L, book.fileIdFor("CBX"))
    }

    @Test
    fun grimmoryWritesPostTheSupportedBulkShapedBodies() = runTest {
        val seen = mutableListOf<Triple<String, String, String>>()
        val engine = MockEngine { request ->
            seen += Triple(request.method.value, request.url.encodedPath, request.body.toByteArray().decodeToString())
            respond("", HttpStatusCode.NoContent)
        }
        val api = NativeProgressApi(
            HttpClient(engine) { install(ContentNegotiation) { json(Json { explicitNulls = false }) } },
        )
        api.grimmorySaveProgress(
            "https://g.test/api/v1/books/progress",
            GrimmoryUpdateProgress(767, GrimmoryFileProgress(bookFileId = 900, positionData = "5000", progressPercent = 25.0)),
        )
        api.grimmorySetStatus("https://g.test/api/v1/books/status", GrimmoryStatusUpdate(listOf(767), "READ"))

        val (save, status) = seen
        assertEquals("POST", save.first)
        assertEquals("/api/v1/books/progress", save.second)
        assertEquals("""{"bookId":767,"fileProgress":{"bookFileId":900,"positionData":"5000","progressPercent":25.0}}""", save.third)
        assertEquals("POST", status.first)
        assertEquals("/api/v1/books/status", status.second)
        assertEquals("""{"bookIds":[767],"status":"READ"}""", status.third)
    }
}
