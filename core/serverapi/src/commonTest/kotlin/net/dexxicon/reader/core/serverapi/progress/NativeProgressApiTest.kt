package net.dexxicon.reader.core.serverapi.progress

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
    fun grimmoryProgressParsesTheNestedAudiobookShape() = runTest {
        val dto = mockApi(
            """
            {
              "readProgress": 93.9,
              "readStatus": "READING",
              "audiobookProgress": {
                "positionMs": 72302094,
                "trackIndex": 0,
                "percentage": 93.9,
                "updatedAt": "2026-09-06T20:04:38Z"
              }
            }
            """.trimIndent(),
        ).grimmoryProgress("https://example.test/x").body()
        assertEquals(72302094L, dto?.audiobookProgress?.positionMs)
        assertEquals(93.9, dto?.audiobookProgress?.percentage)
        assertEquals("2026-09-06T20:04:38Z", dto?.audiobookProgress?.updatedAt)
    }

    @Test
    fun grimmoryAppBookExposesFilesWithAnIdAndType() = runTest {
        val book = mockApi("""{"id":767,"files":[{"id":900,"bookType":"AUDIOBOOK","primary":true}]}""")
            .grimmoryAppBook("https://example.test/x")
        val file = book.files.single()
        assertEquals(900L, file.id)
        assertEquals("AUDIOBOOK", file.bookType)
        assertTrue(file.isPrimaryFile)
    }
}
