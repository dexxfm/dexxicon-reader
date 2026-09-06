package net.dexxicon.reader.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Provider

/**
 * Attaches `Authorization` to every request that belongs to a known server, and retries
 * once with a refreshed token on a 401.
 *
 * [provider] is injected lazily: `:core:data` supplies the real implementation but depends
 * on `:core:network`, so the binding is resolved on first use rather than at graph creation.
 */
class AuthInterceptor @Inject constructor(
    private val provider: Provider<AuthHeaderProvider>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        if (original.header(HEADER) != null) {
            return chain.proceed(original)
        }

        val headerProvider = provider.get()
        val header = headerProvider.authHeader(original.url)
            ?: return chain.proceed(original)

        val response = chain.proceed(original.newBuilder().header(HEADER, header).build())

        if (response.code != HttpURLConnection.HTTP_UNAUTHORIZED) {
            return response
        }

        val refreshed = headerProvider.refreshAuthHeader(original.url) ?: return response
        response.close()
        return chain.proceed(original.newBuilder().header(HEADER, refreshed).build())
    }

    private companion object {
        const val HEADER = "Authorization"
    }
}
