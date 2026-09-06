package net.dexxicon.reader.core.serverapi.auth

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** A freshly issued token set from a native login/refresh. */
data class NativeSession(
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch millis when [accessToken] should be treated as expired (with skew applied). */
    val expiresAtMillis: Long,
)

/**
 * Wraps [NativeAuthApi] with per-server endpoint paths and expiry handling. Stateless —
 * token caching lives in `TokenManager` (`:core:data`).
 */
class NativeAuthClient @Inject constructor(
    private val api: NativeAuthApi,
) {
    suspend fun login(server: Server, username: String, password: String): Outcome<NativeSession> =
        runCatching { api.login(server.resolve(LOGIN_PATH), LoginRequest(username, password)) }
            .fold(::toSession, ::toError)

    suspend fun refresh(server: Server, refreshToken: String): Outcome<NativeSession> =
        runCatching { api.refresh(server.resolve(REFRESH_PATH), RefreshRequest(refreshToken)) }
            .fold(::toSession, ::toError)

    private fun toSession(response: LoginResponse): Outcome<NativeSession> {
        val token = response.accessToken
            ?: return Outcome.Failure(DexxiconError.Parse("No access token in response"))
        return Outcome.Success(
            NativeSession(
                accessToken = token,
                refreshToken = response.refreshToken,
                expiresAtMillis = System.currentTimeMillis() +
                    ((response.expires ?: DEFAULT_TTL_SECONDS) * 1000L) - EXPIRY_SKEW_MILLIS,
            ),
        )
    }

    private fun toError(t: Throwable): Outcome<NativeSession> = when (t) {
        is HttpException -> if (t.code() == 401 || t.code() == 403) {
            Outcome.Failure(DexxiconError.Unauthorized("Invalid username or password"))
        } else {
            Outcome.Failure(DexxiconError.Network("Server returned HTTP ${t.code()}"))
        }
        is IOException -> Outcome.Failure(DexxiconError.Network(t.message ?: "Network error"))
        else -> Outcome.Failure(DexxiconError.Unknown(t.message, t))
    }

    companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REFRESH_PATH = "/api/v1/auth/refresh"
        private const val DEFAULT_TTL_SECONDS = 840L
        private const val EXPIRY_SKEW_MILLIS = 30_000L

        /** Guess the server family from the shape of a login response. */
        fun detectType(response: LoginResponse): ServerType = when {
            response.refreshToken != null -> ServerType.GRIMMORY
            response.user != null -> ServerType.BOOKORBIT
            else -> ServerType.GENERIC
        }
    }
}
