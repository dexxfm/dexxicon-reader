package net.dexxicon.reader.core.serverapi.progress

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import net.dexxicon.reader.core.serverapi.NullableBodyConverterFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class NativeProgressApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NativeProgressApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(
                NullableBodyConverterFactory(json.asConverterFactory("application/json".toMediaType())),
            )
            .build()
            .create(NativeProgressApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `bookOrbit audio-progress tolerates a literal null body`() = runTest {
        server.enqueue(MockResponse().setBody("null").setHeader("Content-Type", "application/json"))
        val response = api.bookOrbitAudioProgress(server.url("/x").toString())
        assertThat(response.isSuccessful).isTrue()
        assertThat(response.body()).isNull()
    }

    @Test
    fun `bookOrbit audio-progress parses a populated body`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"currentFileId":42,"positionSeconds":123.5,"percentage":12.3}""",
            ),
        )
        val dto = api.bookOrbitAudioProgress(server.url("/x").toString()).body()
        assertThat(dto?.currentFileId).isEqualTo(42L)
        assertThat(dto?.positionSeconds).isEqualTo(123.5)
        assertThat(dto?.percentage).isEqualTo(12.3)
    }

    @Test
    fun `grimmory progress parses the nested audiobook shape`() = runTest {
        server.enqueue(
            MockResponse().setBody(
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
            ),
        )
        val dto = api.grimmoryProgress(server.url("/x").toString()).body()
        assertThat(dto?.audiobookProgress?.positionMs).isEqualTo(72302094L)
        assertThat(dto?.audiobookProgress?.percentage).isEqualTo(93.9)
        assertThat(dto?.audiobookProgress?.updatedAt).isEqualTo("2026-09-06T20:04:38Z")
    }

    @Test
    fun `grimmory app book exposes files with an id and type`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":767,"files":[{"id":900,"bookType":"AUDIOBOOK","primary":true}]}""",
            ),
        )
        val book = api.grimmoryAppBook(server.url("/x").toString())
        val file = book.files.single()
        assertThat(file.id).isEqualTo(900L)
        assertThat(file.bookType).isEqualTo("AUDIOBOOK")
        assertThat(file.isPrimaryFile).isTrue()
    }
}
