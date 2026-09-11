package net.dexxicon.reader.core.serverapi.user

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

/**
 * "Who am I" against a native server, so Settings can show which account the app is
 * actually signed in as:
 *   BookOrbit  GET /api/v1/auth/me
 *   Grimmory   GET /api/v1/users/me
 * Both answer with at least `username` / `name` / `email`.
 */
class NativeUserApi(private val client: HttpClient) {

    suspend fun me(url: String): NativeUser = client.get(url).body()
}

@Serializable
data class NativeUser(
    val username: String? = null,
    val name: String? = null,
    val email: String? = null,
)
