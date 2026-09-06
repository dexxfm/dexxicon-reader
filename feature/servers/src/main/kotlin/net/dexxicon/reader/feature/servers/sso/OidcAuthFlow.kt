package net.dexxicon.reader.feature.servers.sso

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import net.dexxicon.reader.core.serverapi.oidc.OidcHandshake
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/** App-registered redirect target (see `appAuthRedirectScheme` in app/build.gradle.kts). */
const val OIDC_REDIRECT_URI = "net.dexxicon.reader://oauth2redirect"

/** Result of the browser auth step: the code + the PKCE verifier + nonce for the exchange. */
sealed interface OidcAuthResult {
    data class Code(
        val code: String,
        val codeVerifier: String,
        val nonce: String,
    ) : OidcAuthResult
    data class Failed(val message: String) : OidcAuthResult
    data object Cancelled : OidcAuthResult
}

/**
 * Thin wrapper over AppAuth. We only use it to obtain an authorization *code* + PKCE
 * verifier — the token exchange happens server-side (BookLore / BookOrbit callback).
 */
class OidcAuthFlow(context: Context) {

    private val service = AuthorizationService(context.applicationContext)

    fun authorizationIntent(handshake: OidcHandshake): Intent {
        val config = AuthorizationServiceConfiguration(
            Uri.parse(handshake.authorizationEndpoint),
            Uri.parse(handshake.tokenEndpoint ?: handshake.authorizationEndpoint),
        )
        val request = AuthorizationRequest.Builder(
            config,
            handshake.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(OIDC_REDIRECT_URI),
        )
            .setScope(handshake.scopes)
            .setState(handshake.state)
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    fun parseResult(data: Intent?): OidcAuthResult {
        if (data == null) {
            Log.i(TAG, "auth result: no data (cancelled/dismissed)")
            return OidcAuthResult.Cancelled
        }
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        Log.i(TAG, "auth result: code=${response?.authorizationCode != null} err=${error?.error}")
        return when {
            response?.authorizationCode != null -> OidcAuthResult.Code(
                code = response.authorizationCode!!,
                codeVerifier = response.request.codeVerifier
                    ?: return OidcAuthResult.Failed("Missing PKCE verifier"),
                nonce = response.request.nonce.orEmpty(),
            )
            error != null && error.code == AuthorizationException.AuthorizationRequestErrors
                .STATE_MISMATCH.code -> OidcAuthResult.Failed("Security check failed, try again")
            error != null -> OidcAuthResult.Failed(error.errorDescription ?: "Sign-in failed")
            else -> OidcAuthResult.Cancelled
        }
    }

    fun dispose() = service.dispose()

    private companion object {
        const val TAG = "DexxiconOidc"
    }
}
