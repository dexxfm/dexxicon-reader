package net.dexxicon.reader.core.serverapi.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Native login for both supported server families:
 *  - Grimmory / BookLore: `POST /api/v1/auth/login` -> access + refresh token, `expires` (s)
 *  - BookOrbit:            `POST /api/v1/auth/login` -> access token + `user`, no refresh
 */
interface NativeAuthApi {

    @POST
    suspend fun login(
        @Url url: String,
        @Body body: LoginRequest,
    ): LoginResponse

    @POST
    suspend fun refresh(
        @Url url: String,
        @Body body: RefreshRequest,
    ): LoginResponse

    /** BookOrbit: refresh token travels as an HttpOnly cookie, so no request body. */
    @POST
    suspend fun refreshWithCookie(@Url url: String): LoginResponse
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
