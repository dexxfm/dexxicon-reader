package net.dexxicon.reader.core.data.auth

import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.serverapi.oidc.OidcClient
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Orchestrates the OIDC login: discovery, then code exchange + persistence. */
class OidcAuthenticator @Inject constructor(
    private val oidcClient: OidcClient,
    private val serverRepository: ServerRepository,
    private val readingProgressRepository: ReadingProgressRepository,
    private val tokenManager: TokenManager,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
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
                readingProgressRepository.seedFromServerAsync(saved.id)
                Outcome.Success(saved)
            }
        }
    }
}
