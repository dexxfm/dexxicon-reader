package net.dexxicon.reader.core.serverapi.oidc

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OIDC endpoints for the two server families.
 *
 * Grimmory / BookLore:
 *   GET  /api/v1/public-settings          -> issuer + clientId + scopes
 *   GET  /api/v1/auth/oidc/state          -> { state }
 *   GET  {issuer}/.well-known/openid-configuration
 *   POST /api/v1/auth/oidc/mobile/callback  { code, codeVerifier, redirectUri, nonce, state }
 *
 * BookOrbit:
 *   GET  /api/v1/app-settings/oidc/providers/public  -> [ { slug, enabled, clientId, scopes } ]
 *   POST /api/v1/auth/oidc/{slug}/state              -> { state, authorizationEndpoint }
 *   POST /api/v1/auth/oidc/callback                  { code, codeVerifier, redirectUri, nonce, state }
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

    /** BookOrbit `/api/v1/auth/oidc/callback` — JSON body. */
    @POST
    suspend fun exchangeJson(
        @Url url: String,
        @Body body: OidcExchangeRequest,
    ): net.dexxicon.reader.core.serverapi.auth.LoginResponse

    /** Grimmory/BookLore `/api/v1/auth/oidc/mobile/callback` — form-encoded, snake_case. */
    @FormUrlEncoded
    @POST
    suspend fun exchangeForm(
        @Url url: String,
        @Field("code") code: String,
        @Field("code_verifier") codeVerifier: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("nonce") nonce: String,
        @Field("state") state: String,
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
