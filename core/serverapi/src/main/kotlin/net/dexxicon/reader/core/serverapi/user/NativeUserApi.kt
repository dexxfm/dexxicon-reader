package net.dexxicon.reader.core.serverapi.user

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * "Who am I" against a native server, so Settings can show which account the app is
 * actually signed in as:
 *   BookOrbit  GET /api/v1/auth/me
 *   Grimmory   GET /api/v1/users/me
 * Both answer with at least `username` / `name` / `email`.
 */
interface NativeUserApi {

    @GET
    suspend fun me(@Url url: String): NativeUser
}

@Serializable
data class NativeUser(
    val username: String? = null,
    val name: String? = null,
    val email: String? = null,
)
