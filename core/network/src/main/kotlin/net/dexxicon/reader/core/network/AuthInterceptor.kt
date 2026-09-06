package net.dexxicon.reader.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Provider

/**
 * Attaches `Authorization` to every request that belongs to a known server, and retries
 * once with a refreshed token on a 401.
 *
 * Auth/discovery endpoints are skipped so obtaining a token can't recurse into itself.
 */
class AuthInterceptor @Inject constructor(
    private val provider: Provider<AuthHeaderProvider>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        if (original.header(HEADER) != null || isAuthEndpoint(original.url.encodedPath)) {
            return chain.proceed(original)
        }

        val headerProvider = provider.get()
        val header = headerProvider.authHeader(original.url)
            ?: return chain.proceed(original)

        val response = chain.proceed(original.newBuilder().header(HEADER, header).build())

        if (response.code != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return response
        }

        val refreshed = headerProvider.refreshAuthHeader(original.url)
        if (refreshed == null) {
            Log.i(TAG, "401 on ${original.url.encodedPath} and no refreshed token")
            return response
        }
        response.close()
        return chain.proceed(original.newBuilder().header(HEADER, refreshed).build())
    }

    private fun isAuthEndpoint(path: String): Boolean =
        path.contains("/auth/login") ||
            path.contains("/auth/refresh") ||
            path.contains("/auth/oidc") ||
            path.contains("/public-settings") ||
            path.contains("/.well-known/") ||
            path.endsWith("/oidc/providers/public")

    private companion object {
        const val HEADER = "Authorization"
        const val TAG = "DexxiconAuth"
    }
}
