package net.dexxicon.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/**
 * Retrofit's `Response<T>` equivalent, for the handful of endpoints whose callers need to
 * inspect a non-2xx status without an exception instead of the usual [createHttpClient]
 * throw-on-error behaviour — servers that answer 200 with a literal `null` body for "no
 * data yet", or callers checking a specific status code (e.g. a 409 meaning "already
 * exists"). [body] and [errorBody] are captured eagerly, not `suspend`, mirroring Retrofit's
 * own eager `Response<T>` — this is a single-shot call, not a stream.
 */
class ApiResponse<T> @PublishedApi internal constructor(
    val status: HttpStatusCode,
    private val bodyValue: T?,
    private val errorText: String?,
) {
    val isSuccessful: Boolean get() = status.isSuccess()
    fun code(): Int = status.value
    fun body(): T? = bodyValue
    fun errorBody(): String? = errorText
}

/**
 * Runs a request against [url] with `expectSuccess = false` (overriding the shared client's
 * default) and wraps the result as an [ApiResponse]. Set the HTTP method, headers, and body
 * inside [block] the same way you would for [io.ktor.client.request.request] directly — e.g.
 * `method = HttpMethod.Put; setBody(...)`.
 *
 * [T] is only deserialized on a 2xx status, and a failed deserialization there (e.g. a
 * literal `null` body some servers send for "nothing yet") becomes a null [ApiResponse.body]
 * rather than a thrown exception — the same tolerance `NullableBodyConverterFactory` gave
 * the Retrofit-based APIs this replaces.
 */
suspend inline fun <reified T> HttpClient.apiResponse(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {},
): ApiResponse<T> {
    val response: HttpResponse = request(url) {
        expectSuccess = false
        block()
    }
    val body = if (response.status.isSuccess()) {
        runCatching { response.body<T>() }.getOrNull()
    } else {
        null
    }
    val error = if (!response.status.isSuccess()) {
        runCatching { response.bodyAsText() }.getOrNull()
    } else {
        null
    }
    return ApiResponse(response.status, body, error)
}
