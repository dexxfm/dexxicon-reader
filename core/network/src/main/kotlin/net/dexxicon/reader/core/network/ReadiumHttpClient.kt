package net.dexxicon.reader.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.IOException

/**
 * A Readium [HttpClient] backed by the app's shared OkHttp client — so publications stream
 * through the same connection pool, timeouts, logging and (crucially) the
 * `Authorization` header that [AuthInterceptor] attaches per server.
 */
class ReadiumHttpClient(
    private val okHttpClient: OkHttpClient,
) : HttpClient {

    override suspend fun stream(
        request: HttpRequest,
    ): Try<HttpStreamResponse, HttpError> = withContext(Dispatchers.IO) {
        val okRequest = try {
            request.toOkHttpRequest()
        } catch (e: Exception) {
            return@withContext Try.failure(HttpError.MalformedResponse(ThrowableError(e)))
        }

        try {
            val response = okHttpClient.newCall(okRequest).execute()
            val headers: Map<String, List<String>> = response.headers.toMultimap()
            val mediaType = response.header("Content-Type")
                ?.let { MediaType(it) }
                ?: MediaType.BINARY
            val finalUrl = AbsoluteUrl(response.request.url.toString()) ?: request.url

            if (!response.isSuccessful) {
                val body = response.body?.bytes()
                response.close()
                return@withContext Try.failure(
                    HttpError.ErrorResponse(HttpStatus(response.code), mediaType, body),
                )
            }

            val httpResponse = HttpResponse(
                request = request,
                url = finalUrl,
                statusCode = HttpStatus(response.code),
                headers = headers,
                mediaType = mediaType,
            )
            val stream = response.body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0))
            Try.success(HttpStreamResponse(httpResponse, stream))
        } catch (e: IOException) {
            Try.failure(HttpError.IO(e))
        }
    }


    private fun HttpRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url.toString())
        headers.forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        val method = method.name
        val body = when (method) {
            "GET", "HEAD" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        builder.method(method, body)
        return builder.build()
    }
}
