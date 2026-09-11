package net.dexxicon.reader.core.serverapi.auth

import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType

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
@OptIn(ExperimentalTime::class)
class NativeAuthClient(
    private val api: NativeAuthApi,
) {
    suspend fun login(server: Server, username: String, password: String): Outcome<NativeSession> =
        runCatching { api.login(server.resolve(LOGIN_PATH), LoginRequest(username, password)) }
            .fold(::toSession, ::toError)

    suspend fun refresh(server: Server, refreshToken: String): Outcome<NativeSession> =
        runCatching { api.refresh(server.resolve(REFRESH_PATH), RefreshRequest(refreshToken)) }
            .fold(::toSession, ::toError)

    /** BookOrbit: the `refresh_token` cookie in the shared jar authorises this call. */
    suspend fun refreshViaCookie(server: Server): Outcome<NativeSession> =
        runCatching { api.refreshWithCookie(server.resolve(REFRESH_PATH)) }
            .fold(::toSession, ::toError)

    private fun toSession(response: LoginResponse): Outcome<NativeSession> {
        val token = response.accessToken
            ?: return Outcome.Failure(DexxiconError.Parse("No access token in response"))
        return Outcome.Success(
            NativeSession(
                accessToken = token,
                refreshToken = response.refreshToken,
                expiresAtMillis = Clock.System.now().toEpochMilliseconds() +
                    ((response.expires ?: DEFAULT_TTL_SECONDS) * 1000L) - EXPIRY_SKEW_MILLIS,
            ),
        )
    }

    private fun toError(t: Throwable): Outcome<NativeSession> = when (t) {
        // expectSuccess = true (the shared Ktor client, :core:network's createHttpClient)
        // throws this for any non-2xx — the Ktor equivalent of Retrofit's HttpException.
        is ResponseException -> {
            val status = t.response.status.value
            if (status == 401 || status == 403) {
                Outcome.Failure(DexxiconError.Unauthorized("Invalid username or password"))
            } else {
                Outcome.Failure(DexxiconError.Network("Server returned HTTP $status"))
            }
        }
        // kotlinx.io.IOException is Ktor 3.x's multiplatform connection-failure type — on
        // the JVM/Android target it's an actual typealias for java.io.IOException, so this
        // matches the pre-KMP behaviour exactly there; on iOS it covers the Darwin engine's
        // equivalent failures the same way.
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
