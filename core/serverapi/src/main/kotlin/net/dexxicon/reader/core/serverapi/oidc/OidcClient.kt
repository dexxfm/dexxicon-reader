package net.dexxicon.reader.core.serverapi.oidc

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.auth.NativeSession
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** Everything the app needs to launch the browser auth-code flow for one server. */
data class OidcHandshake(
    val authorizationEndpoint: String,
    val tokenEndpoint: String?,
    val clientId: String,
    val scopes: String,
    val state: String,
    val exchangeUrl: String,
    val serverType: ServerType,
    val providerName: String?,
)

class OidcClient @Inject constructor(
    private val api: OidcApi,
) {
    /** Discovers OIDC config + a fresh `state` for [server]. */
    suspend fun beginHandshake(server: Server): Outcome<OidcHandshake> = when (server.type) {
        ServerType.BOOKORBIT -> runCatching { bookorbitHandshake(server) }.getOrElse { it.toFailure() }
        ServerType.GRIMMORY -> runCatching { bookloreHandshake(server) }.getOrElse { it.toFailure() }
        ServerType.GENERIC -> {
            val booklore = runCatching { bookloreHandshake(server) }.getOrElse { it.toFailure() }
            if (booklore is Outcome.Success) {
                booklore
            } else {
                runCatching { bookorbitHandshake(server) }.getOrElse { booklore }
            }
        }
    }

    /** Exchanges the browser's auth code for a native session. */
    suspend fun exchange(
        handshake: OidcHandshake,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        nonce: String,
    ): Outcome<NativeSession> = runCatching {
        val response = when (handshake.serverType) {
            ServerType.BOOKORBIT -> api.exchangeJson(
                handshake.exchangeUrl,
                OidcExchangeRequest(code, codeVerifier, redirectUri, nonce, handshake.state),
            )
            else -> api.exchangeForm(
                url = handshake.exchangeUrl,
                code = code,
                codeVerifier = codeVerifier,
                redirectUri = redirectUri,
                nonce = nonce,
                state = handshake.state,
            )
        }
        val token = response.accessToken
            ?: return Outcome.Failure(DexxiconError.Parse("OIDC exchange returned no token"))
        Outcome.Success(
            NativeSession(
                accessToken = token,
                refreshToken = response.refreshToken,
                expiresAtMillis = System.currentTimeMillis() +
                    ((response.expires ?: 840L) * 1000L) - 30_000L,
            ),
        )
    }.getOrElse { it.toFailure() }

    private suspend fun bookloreHandshake(server: Server): Outcome<OidcHandshake> {
        val settings = api.bookloreSettings(server.resolve(BOOKLORE_SETTINGS))
        val details = settings.oidcProviderDetails
        if (!settings.oidcEnabled || details == null || details.issuerUri.isNullOrBlank()) {
            return Outcome.Failure(DexxiconError.Unsupported("SSO is not enabled on this server"))
        }
        val discovery = api.openIdConfiguration(
            details.issuerUri.trimEnd('/') + OPENID_CONFIG,
        )
        val authEndpoint = discovery.authorization_endpoint
            ?: return Outcome.Failure(DexxiconError.Parse("No authorization_endpoint in discovery"))
        val state = api.bookloreState(server.resolve(BOOKLORE_STATE)).state
        return Outcome.Success(
            OidcHandshake(
                authorizationEndpoint = authEndpoint,
                tokenEndpoint = discovery.token_endpoint,
                clientId = details.clientId,
                scopes = details.scopes.ifBlank { DEFAULT_SCOPES },
                state = state,
                exchangeUrl = server.resolve(BOOKLORE_MOBILE_CALLBACK),
                serverType = ServerType.GRIMMORY,
                providerName = details.providerName,
            ),
        )
    }

    private suspend fun bookorbitHandshake(server: Server): Outcome<OidcHandshake> {
        val providers = api.bookorbitProviders(server.resolve(BOOKORBIT_PROVIDERS))
        val provider = providers.firstOrNull { it.enabled }
            ?: return Outcome.Failure(DexxiconError.Unsupported("No SSO provider is enabled"))
        val stateResponse = api.bookorbitState(
            server.resolve(BOOKORBIT_STATE.format(provider.slug)),
        )
        val authEndpoint = stateResponse.authorizationEndpoint
            ?: provider.authorizationEndpoint
            ?: return Outcome.Failure(DexxiconError.Parse("SSO provider is unreachable"))
        return Outcome.Success(
            OidcHandshake(
                authorizationEndpoint = authEndpoint,
                tokenEndpoint = null,
                clientId = provider.clientId,
                scopes = provider.scopes.ifBlank { DEFAULT_SCOPES },
                state = stateResponse.state,
                exchangeUrl = server.resolve(BOOKORBIT_CALLBACK),
                serverType = ServerType.BOOKORBIT,
                providerName = provider.label,
            ),
        )
    }

    private fun Throwable.toFailure(): Outcome<Nothing> = when (this) {
        is HttpException -> {
            val detail = runCatching { response()?.errorBody()?.string() }.getOrNull()
                ?.take(160)?.takeIf { it.isNotBlank() }
            Outcome.Failure(
                DexxiconError.Network("SSO exchange failed (HTTP ${code()})${detail?.let { " — $it" } ?: ""}"),
            )
        }
        is IOException -> Outcome.Failure(DexxiconError.Network(message ?: "Network error"))
        else -> Outcome.Failure(DexxiconError.Unknown(message, this))
    }

    companion object {
        const val BOOKLORE_SETTINGS = "/api/v1/public-settings"
        const val BOOKLORE_STATE = "/api/v1/auth/oidc/state"
        const val BOOKLORE_MOBILE_CALLBACK = "/api/v1/auth/oidc/mobile/callback"
        const val BOOKORBIT_PROVIDERS = "/api/v1/app-settings/oidc/providers/public"
        const val BOOKORBIT_STATE = "/api/v1/auth/oidc/%s/state"
        const val BOOKORBIT_CALLBACK = "/api/v1/auth/oidc/callback"
        const val OPENID_CONFIG = "/.well-known/openid-configuration"
        const val DEFAULT_SCOPES = "openid profile email offline_access"
    }
}
