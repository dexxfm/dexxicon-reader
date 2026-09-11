package net.dexxicon.reader.core.serverapi.oidc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.serverapi.auth.LoginResponse

/**
 * OIDC endpoints for the two server families. Both use an https/http redirect to the
 * server's own `/oauth2-callback` (captured by an in-app WebView) and exchange the code at
 * `POST /api/v1/auth/oidc/callback` with a JSON body -> access + refresh token.
 *
 * Grimmory / BookLore:
 *   GET  /api/v1/public-settings                     -> issuer + clientId + scopes
 *   GET  /api/v1/auth/oidc/state                     -> { state }
 *   GET  {issuer}/.well-known/openid-configuration
 * BookOrbit:
 *   GET  /api/v1/app-settings/oidc/providers/public  -> [ { slug, enabled, clientId, scopes } ]
 *   POST /api/v1/auth/oidc/{slug}/state              -> { state, authorizationEndpoint }
 */
class OidcApi(private val client: HttpClient) {

    suspend fun bookloreSettings(url: String): BooklorePublicSettings = client.get(url).body()

    suspend fun bookloreState(url: String): OidcStateResponse = client.get(url).body()

    suspend fun openIdConfiguration(url: String): OpenIdConfiguration = client.get(url).body()

    suspend fun bookorbitProviders(url: String): List<OidcProvider> = client.get(url).body()

    suspend fun bookorbitState(url: String): OidcStateResponse = client.post(url).body()

    /** `POST /api/v1/auth/oidc/callback` — JSON body, returns access + refresh token. */
    suspend fun exchangeJson(url: String, body: OidcExchangeRequest): LoginResponse =
        client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
}

@Serializable
data class OpenIdConfiguration(
    val issuer: String? = null,
    val authorization_endpoint: String? = null,
    val token_endpoint: String? = null,
    val end_session_endpoint: String? = null,
)

@Serializable
data class OidcStateResponse(
    val state: String,
    val authorizationEndpoint: String? = null,
)

@Serializable
data class OidcProvider(
    val slug: String,
    val enabled: Boolean = false,
    val name: String? = null,
    val displayName: String? = null,
    val clientId: String = "",
    val scopes: String = "openid profile email",
    val issuerUri: String? = null,
    val authorizationEndpoint: String? = null,
) {
    val label: String? get() = displayName ?: name
}

@Serializable
data class OidcExchangeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val nonce: String,
    val state: String,
)

/** Shape of Grimmory's `/api/v1/public-settings`. */
@Serializable
data class BooklorePublicSettings(
    val oidcEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
    val oidcProviderDetails: BookloreOidcDetails? = null,
)

@Serializable
data class BookloreOidcDetails(
    val clientId: String = "",
    val issuerUri: String? = null,
    val providerName: String? = null,
    val scopes: String = "",
)
