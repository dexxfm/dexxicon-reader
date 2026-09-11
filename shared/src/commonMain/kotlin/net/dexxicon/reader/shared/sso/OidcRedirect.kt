package net.dexxicon.reader.shared.sso

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake

/**
 * The WebView-flow business logic — building the authorize URL and reading the redirect back
 * off it — lives here in commonMain, on Ktor's multiplatform `Url`/`URLBuilder`
 * (`:core:network`'s existing dependency), rather than duplicated per platform on
 * `android.net.Uri` and `NSURLComponents`. The platform [SsoWebView] actuals only have to
 * host a WebView and report which URL it's navigating to — see that file's doc comment.
 */

/** The provider's authorize page, with PKCE params attached. */
fun buildAuthorizeUrl(handshake: OidcHandshake, pkce: Pkce): String =
    URLBuilder(handshake.authorizationEndpoint).apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", handshake.clientId)
        parameters.append("redirect_uri", handshake.redirectUri)
        parameters.append("scope", handshake.scopes)
        parameters.append("state", handshake.state)
        parameters.append("nonce", pkce.nonce)
        parameters.append("code_challenge", pkce.challenge)
        parameters.append("code_challenge_method", "S256")
    }.buildString()

sealed interface OidcRedirectResult {
    data class Code(val code: String) : OidcRedirectResult
    data class Error(val message: String) : OidcRedirectResult
}

/**
 * Reads `code`/`state`/`error` off a captured redirect URL (one the platform [SsoWebView]
 * actual has already confirmed starts with [OidcHandshake.redirectUri]) and checks the OAuth
 * `state` parameter against the one this handshake started with.
 */
fun readOidcRedirect(capturedUrl: String, handshake: OidcHandshake): OidcRedirectResult {
    val params = Url(capturedUrl).parameters
    params["error"]?.let { error ->
        return OidcRedirectResult.Error(params["error_description"] ?: error)
    }
    val code = params["code"]
        ?: return OidcRedirectResult.Error("No authorization code was returned")
    if (params["state"] != handshake.state) {
        return OidcRedirectResult.Error("Security check failed — try again")
    }
    return OidcRedirectResult.Code(code)
}
