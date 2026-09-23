package net.dexxicon.reader.core.data

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.net.ConnectException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.model.ServerProbeResult
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import org.junit.Test

/**
 * Issue #267 — "can't connect to a local BookOrbit over HTTP". HTTP itself always worked; a URL
 * typed without a scheme was silently sent over HTTPS, and a plain-HTTP server answered that
 * with a bare "Unable to parse TLS packet header".
 */
class ServerProberTest {

    private val requested = mutableListOf<String>()

    /** [httpsFails]/[httpFails]: what each scheme's connection attempt throws, null = login OK. */
    private fun prober(httpsFails: Exception? = null, httpFails: Exception? = null): ServerProber {
        val engine = MockEngine { request ->
            requested += request.url.toString()
            val failure = if (request.url.protocol.name == "https") httpsFails else httpFails
            if (failure != null) throw failure
            respond(
                """{"accessToken":"tok","user":{"id":1,"username":"test"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return ServerProber(NativeAuthClient(NativeAuthApi(client)), Dispatchers.Unconfined)
    }

    private val tls = SSLException("Unable to parse TLS packet header")

    @Test
    fun `no scheme falls back to http when https can't connect`() = runBlocking<Unit> {
        val result = prober(httpsFails = tls).probe("192.168.1.200:3001", "test", "pw")

        assertThat(result).isInstanceOf(ServerProbeResult.Success::class.java)
        result as ServerProbeResult.Success
        assertThat(result.baseUrl).isEqualTo("http://192.168.1.200:3001")
        assertThat(result.detectedType).isEqualTo(ServerType.BOOKORBIT)
        assertThat(requested).containsExactly(
            "https://192.168.1.200:3001/api/v1/auth/login",
            "http://192.168.1.200:3001/api/v1/auth/login",
        ).inOrder()
    }

    @Test
    fun `no scheme prefers https when it works`() = runBlocking<Unit> {
        val result = prober().probe("books.example.com/", "test", "pw") as ServerProbeResult.Success

        assertThat(result.baseUrl).isEqualTo("https://books.example.com")
        assertThat(requested).hasSize(1)
    }

    @Test
    fun `an explicit scheme is used exactly as typed, never swapped`() = runBlocking<Unit> {
        val result = prober(httpsFails = tls).probe("https://192.168.1.200:3001", "test", "pw")

        assertThat(requested).containsExactly("https://192.168.1.200:3001/api/v1/auth/login")
        assertThat(result).isInstanceOf(ServerProbeResult.Unreachable::class.java)
        // …and the TLS failure is explained, not dumped raw.
        assertThat((result as ServerProbeResult.Unreachable).message).contains("start the address with http://")

        val plain = prober(httpsFails = tls).probe("http://192.168.1.200:3001", "test", "pw")
        assertThat((plain as ServerProbeResult.Success).baseUrl).isEqualTo("http://192.168.1.200:3001")
    }

    @Test
    fun `nothing answering on either scheme says so`() = runBlocking<Unit> {
        val refused = ConnectException("Connection refused")
        val result = prober(httpsFails = refused, httpFails = refused).probe("10.0.0.9:3001", "test", "pw")

        assertThat(result).isInstanceOf(ServerProbeResult.Unreachable::class.java)
        assertThat((result as ServerProbeResult.Unreachable).message).contains("over HTTPS or HTTP")
    }
}
