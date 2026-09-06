package net.dexxicon.reader.core.network

import okhttp3.HttpUrl

/**
 * Supplies the `Authorization` header for outbound requests. Implemented in `:core:data`
 * (which knows about servers + credentials); consumed by [AuthInterceptor] here so the
 * network layer stays free of persistence dependencies.
 *
 * Both methods are called on an OkHttp dispatcher thread and may block briefly.
 */
interface AuthHeaderProvider {

    /** Header value for [url], or null if this URL is not tied to a known server. */
    fun authHeader(url: HttpUrl): String?

    /**
     * Called after a 401. Should invalidate any cached token for [url]'s server, obtain a
     * fresh one, and return the new header value — or null if re-auth failed.
     */
    fun refreshAuthHeader(url: HttpUrl): String?
}

/** No-op default so the network module is usable before `:core:data` binds a real one. */
object NoAuthHeaderProvider : AuthHeaderProvider {
    override fun authHeader(url: HttpUrl): String? = null
    override fun refreshAuthHeader(url: HttpUrl): String? = null
}
