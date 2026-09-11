package net.dexxicon.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Live for the sign-in path (`:core:serverapi`'s `NativeAuthApi`/`OidcApi`, issue #52) —
 * everything else in `:core:serverapi` still talks through Retrofit + OkHttp for now.
 *
 * Takes an already-built [HttpClientEngine] (not a factory) rather than picking Android's
 * OkHttp engine or iOS's Darwin engine itself, so the caller keeps full control over
 * engine-specific config — on Android, `:app`'s Hilt module builds the OkHttp engine with
 * `preconfigured` set to the same [okhttp3.OkHttpClient] Retrofit and Readium share, so this
 * client's traffic gets the identical cookie jar (BookOrbit's HttpOnly refresh-token cookie
 * depends on this) and logging, without wiring either up twice.
 */
fun createHttpClient(
    engine: HttpClientEngine,
    authHeaderProvider: AuthHeaderProvider,
): HttpClient {
    val client = HttpClient(engine) {
        // Throw ClientRequestException/ServerResponseException (both ResponseException) on
        // any non-2xx, matching Retrofit's HttpException-on-error-status behaviour.
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
        }
    }
    client.installAuthHeaderPlugin(authHeaderProvider)
    return client
}

/**
 * Ktor equivalent of `AuthInterceptor` (androidMain): attaches `Authorization` to every
 * request that belongs to a known server, and retries once with a refreshed token on a 401.
 * Auth/discovery endpoints are skipped so obtaining a token can't recurse into itself — same
 * logic as the OkHttp version, just against Ktor's [HttpSend] interception point instead of
 * an `Interceptor` chain.
 */
private fun HttpClient.installAuthHeaderPlugin(provider: AuthHeaderProvider) {
    plugin(HttpSend).intercept { request ->
        if (request.headers.contains(AUTH_HEADER) || isAuthEndpoint(request.url.encodedPath)) {
            return@intercept execute(request)
        }

        val header = provider.authHeader(request.url.build())
            ?: return@intercept execute(request)
        request.headers.set(AUTH_HEADER, header)
        val call = execute(request)

        if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call

        val refreshed = provider.refreshAuthHeader(request.url.build())
            ?: return@intercept call
        request.headers.set(AUTH_HEADER, refreshed)
        execute(request)
    }
}

private fun isAuthEndpoint(path: String): Boolean =
    path.contains("/auth/login") ||
        path.contains("/auth/refresh") ||
        path.contains("/auth/oidc") ||
        path.contains("/public-settings") ||
        path.contains("/.well-known/") ||
        path.endsWith("/oidc/providers/public")

private const val AUTH_HEADER = "Authorization"
