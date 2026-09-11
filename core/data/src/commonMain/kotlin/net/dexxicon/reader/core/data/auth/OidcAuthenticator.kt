package net.dexxicon.reader.core.data.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.ProgressSeeder
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.oidc.OidcClient
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake

/**
 * Orchestrates the OIDC login: discovery, then code exchange + persistence.
 *
 * [io] is a plain, unqualified [CoroutineDispatcher] rather than `@Dispatcher(IO)` — that
 * qualifier annotation is `javax.inject`-based (Hilt/androidMain-only) and can't live on a
 * commonMain constructor. The `:app`-hosted provider resolves the qualified binding and
 * passes the instance through instead.
 */
class OidcAuthenticator(
    private val oidcClient: OidcClient,
    private val serverRepository: ServerRepository,
    private val progressSeeder: ProgressSeeder,
    private val tokenManager: TokenManager,
    private val io: CoroutineDispatcher,
) {
    suspend fun beginHandshake(server: Server): Outcome<OidcHandshake> =
        withContext(io) { oidcClient.beginHandshake(server) }

    /**
     * Exchanges [code] for a session and persists [pendingServer] as an OIDC server.
     * Returns the saved server on success.
     */
    suspend fun completeAndSave(
        pendingServer: Server,
        handshake: OidcHandshake,
        code: String,
        codeVerifier: String,
        nonce: String,
    ): Outcome<Server> = withContext(io) {
        when (
            val exchange = oidcClient.exchange(
                handshake = handshake,
                code = code,
                codeVerifier = codeVerifier,
                nonce = nonce,
            )
        ) {
            is Outcome.Failure -> exchange
            is Outcome.Success -> {
                val saved = serverRepository.save(
                    server = pendingServer.copy(
                        authMode = AuthMode.OIDC,
                        type = handshake.serverType,
                    ),
                    password = null,
                )
                tokenManager.seedSession(saved.id, exchange.value)
                progressSeeder.seedFromServerAsync(saved.id)
                Outcome.Success(saved)
            }
        }
    }
}
