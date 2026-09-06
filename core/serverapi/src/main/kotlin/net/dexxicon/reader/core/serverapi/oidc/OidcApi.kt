package net.dexxicon.reader.core.serverapi.oidc

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

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
interface OidcApi {

    @GET
    suspend fun bookloreSettings(@Url url: String): BooklorePublicSettings

    @GET
    suspend fun bookloreState(@Url url: String): OidcStateResponse

    @GET
    suspend fun openIdConfiguration(@Url url: String): OpenIdConfiguration

    @GET
    suspend fun bookorbitProviders(@Url url: String): List<OidcProvider>

    @POST
    suspend fun bookorbitState(@Url url: String): OidcStateResponse

    /** `POST /api/v1/auth/oidc/callback` — JSON body, returns access + refresh token. */
    @POST
    suspend fun exchangeJson(
        @Url url: String,
        @Body body: OidcExchangeRequest,
    ): net.dexxicon.reader.core.serverapi.auth.LoginResponse
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
