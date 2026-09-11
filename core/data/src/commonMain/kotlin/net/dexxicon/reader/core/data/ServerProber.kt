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
    suspend fun probe(
        baseUrl: String,
        username: String,
        password: String,
    ): ServerProbeResult = withContext(io) {
        val normalized = baseUrl.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }.trimEnd('/')

        val candidate = Server(
            id = "",
            displayName = normalized,
            baseUrl = normalized,
            authMode = AuthMode.NATIVE,
        )

        when (val result = authClient.login(candidate, username, password)) {
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
                else -> ServerProbeResult.Unreachable(
                    result.error.message ?: "Could not reach the server",
                )
            }
        }
    }
}
