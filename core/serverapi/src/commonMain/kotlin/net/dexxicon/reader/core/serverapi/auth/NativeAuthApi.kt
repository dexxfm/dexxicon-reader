package net.dexxicon.reader.core.serverapi.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Native login for both supported server families:
 *  - Grimmory / BookLore: `POST /api/v1/auth/login` -> access + refresh token, `expires` (s)
 *  - BookOrbit:            `POST /api/v1/auth/login` -> access token + `user`, no refresh
 */
class NativeAuthApi(private val client: HttpClient) {

    suspend fun login(url: String, body: LoginRequest): LoginResponse =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    suspend fun refresh(url: String, body: RefreshRequest): LoginResponse =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    /** BookOrbit: refresh token travels as an HttpOnly cookie, so no request body. */
    suspend fun refreshWithCookie(url: String): LoginResponse = client.post(url).body()
}

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refreshToken") val refreshToken: String,
)

@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Grimmory: seconds until [accessToken] expires. BookOrbit omits it. */
    val expires: Long? = null,
    val user: LoginUser? = null,
)

@Serializable
data class LoginUser(
    val id: Int? = null,
    val username: String? = null,
    val name: String? = null,
)
