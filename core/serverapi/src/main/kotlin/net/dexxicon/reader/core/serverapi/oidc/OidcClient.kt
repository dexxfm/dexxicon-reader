package net.dexxicon.reader.core.serverapi.oidc

import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.auth.NativeSession
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** How the browser step is run. */
enum class OidcFlowKind {
    /** System browser (Custom Tab) + custom-scheme redirect via AppAuth. */
    CUSTOM_SCHEME,

    /** In-app WebView that intercepts an https redirect (servers with no native redirect). */
    WEBVIEW,
}

/** Everything the app needs to run the auth-code flow for one server. */
data class OidcHandshake(
    val authorizationEndpoint: String,
    val tokenEndpoint: String?,
    val clientId: String,
    val scopes: String,
    val state: String,
    val exchangeUrl: String,
    val redirectUri: String,
    val flow: OidcFlowKind,
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

    /** Exchanges the auth code for a native session. */
    suspend fun exchange(
        handshake: OidcHandshake,
        code: String,
        codeVerifier: String,
        nonce: String,
    ): Outcome<NativeSession> = runCatching {
        val response = api.exchangeJson(
            handshake.exchangeUrl,
            OidcExchangeRequest(code, codeVerifier, handshake.redirectUri, nonce, handshake.state),
        )
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
        val discovery = api.openIdConfiguration(details.issuerUri.trimEnd('/') + OPENID_CONFIG)
        val authEndpoint = discovery.authorization_endpoint
            ?: return Outcome.Failure(DexxiconError.Parse("No authorization_endpoint in discovery"))
        val state = api.bookloreState(server.resolve(BOOKLORE_STATE)).state
        return Outcome.Success(
            OidcHandshake(
                authorizationEndpoint = authEndpoint,
                tokenEndpoint = discovery.token_endpoint,
                clientId = details.clientId,
                scopes = withOfflineAccess(details.scopes.ifBlank { DEFAULT_SCOPES }),
                state = state,
                exchangeUrl = server.resolve(BOOKLORE_WEB_CALLBACK),
                // The server's own web callback — already whitelisted in the IdP (the web
                // flow uses it) and BookLore accepts it when the origin matches the server.
                redirectUri = server.normalizedBaseUrl + OAUTH2_CALLBACK_PATH,
                flow = OidcFlowKind.WEBVIEW,
                serverType = ServerType.GRIMMORY,
                providerName = details.providerName,
            ),
        )
    }

    private suspend fun bookorbitHandshake(server: Server): Outcome<OidcHandshake> {
        val providers = api.bookorbitProviders(server.resolve(BOOKORBIT_PROVIDERS))
        val provider = providers.firstOrNull { it.enabled }
            ?: return Outcome.Failure(DexxiconError.Unsupported("No SSO provider is enabled"))
        val stateResponse = api.bookorbitState(server.resolve(BOOKORBIT_STATE.format(provider.slug)))
        val authEndpoint = stateResponse.authorizationEndpoint
            ?: provider.authorizationEndpoint
            ?: return Outcome.Failure(DexxiconError.Parse("SSO provider is unreachable"))
        return Outcome.Success(
            OidcHandshake(
                authorizationEndpoint = authEndpoint,
                tokenEndpoint = null,
                clientId = provider.clientId,
                scopes = withOfflineAccess(provider.scopes.ifBlank { DEFAULT_SCOPES }),
                state = stateResponse.state,
                exchangeUrl = server.resolve(BOOKORBIT_CALLBACK),
                // BookOrbit hardcodes `${appUrl}/oauth2-callback`; capture it with a WebView.
                redirectUri = server.normalizedBaseUrl + OAUTH2_CALLBACK_PATH,
                flow = OidcFlowKind.WEBVIEW,
                serverType = ServerType.BOOKORBIT,
                providerName = provider.label,
            ),
        )
    }

    /**
     * Guarantee `offline_access` is in the requested scopes so the IdP issues a long-lived
     * refresh token — without it an OIDC session dies for good the moment the short access
     * token expires. Harmless when the server config already asked for it.
     */
    private fun withOfflineAccess(scopes: String): String {
        val parts = scopes.split(' ', '\t', '\n').filter { it.isNotBlank() }
        return if (parts.any { it.equals("offline_access", ignoreCase = true) }) {
            parts.joinToString(" ")
        } else {
            (parts + "offline_access").joinToString(" ")
        }
    }

    private fun Throwable.toFailure(): Outcome<Nothing> = when (this) {
        is HttpException -> {
            val detail = runCatching { response()?.errorBody()?.string() }.getOrNull()
                ?.take(200)?.takeIf { it.isNotBlank() }
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
        const val BOOKLORE_WEB_CALLBACK = "/api/v1/auth/oidc/callback"
        const val BOOKORBIT_PROVIDERS = "/api/v1/app-settings/oidc/providers/public"
        const val BOOKORBIT_STATE = "/api/v1/auth/oidc/%s/state"
        const val BOOKORBIT_CALLBACK = "/api/v1/auth/oidc/callback"
        const val OPENID_CONFIG = "/.well-known/openid-configuration"
        const val OAUTH2_CALLBACK_PATH = "/oauth2-callback"
        const val DEFAULT_SCOPES = "openid profile email"
    }
}
