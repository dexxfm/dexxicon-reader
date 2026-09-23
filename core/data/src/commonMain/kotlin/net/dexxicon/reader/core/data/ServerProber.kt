package net.dexxicon.reader.core.data

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerProbeResult
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Verifies a server + credentials before it is saved: tries a native login, and on success
 * reports the detected family so the UI can confirm.
 *
 * [io] is a plain, unqualified [CoroutineDispatcher] rather than `@Dispatcher(IO)` —
 * that qualifier annotation is `javax.inject`-based (Hilt/androidMain-only), so it can't
 * live on a commonMain constructor. The `:app`-hosted provider resolves the qualified
 * binding and passes the instance through instead.
 */
class ServerProber(
    private val authClient: NativeAuthClient,
    private val io: CoroutineDispatcher,
) {
    /**
     * issue #267 — a URL typed without a scheme used to become `https://` unconditionally, so a
     * self-hosted server on a LAN (typically plain HTTP, e.g. `192.168.1.200:3001`) failed with
     * a bare TLS error even though the same address worked in a browser, which falls back to
     * HTTP on its own. Now: an explicit scheme is used exactly as typed; without one, HTTPS is
     * tried first and HTTP only if HTTPS couldn't connect at all. The address that actually
     * worked comes back in [ServerProbeResult.Success.baseUrl] for the caller to save.
     */
    suspend fun probe(
        baseUrl: String,
        username: String,
        password: String,
    ): ServerProbeResult = withContext(io) {
        val typed = baseUrl.trim().trimEnd('/')
        if (typed.hasScheme()) return@withContext attempt(typed, username, password)

        val secure = attempt("https://$typed", username, password)
        if (secure !is ServerProbeResult.Unreachable) return@withContext secure
        when (val plain = attempt("http://$typed", username, password)) {
            is ServerProbeResult.Unreachable -> ServerProbeResult.Unreachable(
                "Couldn't reach $typed over HTTPS or HTTP (${plain.message})",
            )
            else -> plain
        }
    }

    private suspend fun attempt(url: String, username: String, password: String): ServerProbeResult {
        val candidate = Server(
            id = "",
            displayName = url,
            baseUrl = url,
            authMode = AuthMode.NATIVE,
        )
        return when (val result = authClient.login(candidate, username, password)) {
            is Outcome.Success -> {
                val session = result.value
                ServerProbeResult.Success(
                    detectedType = if (session.refreshToken != null) {
                        ServerType.GRIMMORY
                    } else {
                        ServerType.BOOKORBIT
                    },
                    detectedAuthMode = AuthMode.NATIVE,
                    serverName = null,
                    serverVersion = null,
                    baseUrl = url,
                )
            }
            is Outcome.Failure -> when (result.error) {
                is DexxiconError.Unauthorized ->
                    ServerProbeResult.InvalidCredentials(
                        result.error.message ?: "Invalid username or password",
                    )
                is DexxiconError.Parse ->
                    ServerProbeResult.NotAServer(
                        "That URL responded, but not like BookOrbit or Grimmory",
                    )
                else -> ServerProbeResult.Unreachable(unreachableMessage(url, result.error.message))
            }
        }
    }

    /** issue #267 — the raw TLS failure ("Unable to parse TLS packet header") says nothing
     *  useful to someone whose server simply doesn't do HTTPS; say what to do instead. */
    private fun unreachableMessage(url: String, raw: String?): String {
        val tlsFailure = raw != null && (raw.contains("TLS", ignoreCase = true) || raw.contains("SSL", ignoreCase = true))
        return if (url.startsWith("https://", ignoreCase = true) && tlsFailure) {
            "Couldn't make a secure (HTTPS) connection. If this server doesn't use HTTPS, " +
                "start the address with http:// instead."
        } else {
            raw ?: "Could not reach the server"
        }
    }

    private fun String.hasScheme(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
