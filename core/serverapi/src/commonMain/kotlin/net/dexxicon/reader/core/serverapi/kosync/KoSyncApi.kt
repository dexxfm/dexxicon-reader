package net.dexxicon.reader.core.serverapi.kosync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.network.ApiResponse
import net.dexxicon.reader.core.network.apiResponse

/**
 * KOReader sync protocol (`kosync`). Base URL is per-server (BookLore/Grimmory host it at
 * `{host}/api/koreader`, BookOrbit at `{host}/api/v1/koreader`). Auth is a pair of headers:
 * `x-auth-user` = username, `x-auth-key` = md5(password).
 *
 * All three return [ApiResponse] (not a thrown exception on a non-2xx) — callers check
 * `.isSuccessful` themselves rather than treat a rejected sync as a hard failure.
 */
class KoSyncApi(private val client: HttpClient) {

    suspend fun authorize(url: String, user: String, key: String): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Get
            header("x-auth-user", user)
            header("x-auth-key", key)
        }

    suspend fun getProgress(url: String, user: String, key: String): ApiResponse<KoSyncProgressDto> =
        client.apiResponse(url) {
            method = HttpMethod.Get
            header("x-auth-user", user)
            header("x-auth-key", key)
        }

    suspend fun putProgress(
        url: String,
        user: String,
        key: String,
        body: KoSyncProgressUpdate,
    ): ApiResponse<Unit> =
        client.apiResponse(url) {
            method = HttpMethod.Put
            header("x-auth-user", user)
            header("x-auth-key", key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}

@Serializable
data class KoSyncProgressUpdate(
    val document: String,
    val progress: String,
    val percentage: Double,
    val device: String,
    val device_id: String,
)

@Serializable
data class KoSyncProgressDto(
    val document: String? = null,
    val progress: String? = null,
    val percentage: Double? = null,
    val device: String? = null,
    val device_id: String? = null,
    val timestamp: Long? = null,
)
