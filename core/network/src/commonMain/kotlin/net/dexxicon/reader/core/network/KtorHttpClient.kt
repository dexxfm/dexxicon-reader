package net.dexxicon.reader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Not yet wired into any live traffic on either platform — `:core:serverapi` still talks
 * through Retrofit + OkHttp (Android's [AuthInterceptor]/[PersistentCookieJar] included), and
 * nothing on iOS calls this yet either. This exists so `:core:serverapi`'s future Ktor port
 * has a client + auth handling ready to build on, without carrying any regression risk to
 * `:app` today — nothing currently depends on it.
 */
fun createHttpClient(
    engine: HttpClientEngineFactory<*>,
    authHeaderProvider: AuthHeaderProvider,
): HttpClient {
    val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
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
