package net.dexxicon.reader.core.network

import io.ktor.http.Url

/**
 * Supplies the `Authorization` header for outbound requests. Implemented in `:core:data`
 * (which knows about servers + credentials); consumed by the platform HTTP client's auth
 * interceptor/plugin so the network layer stays free of persistence dependencies.
 *
 * Both methods may block briefly (a local DB read, or a token refresh call) — callers on
 * Android already run them off an OkHttp dispatcher thread; a Ktor plugin equivalent should
 * do the same off its own IO dispatcher rather than the caller's.
 */
interface AuthHeaderProvider {

    /** Header value for [url], or null if this URL is not tied to a known server. */
    fun authHeader(url: Url): String?

    /**
     * Called after a 401 for a request sent with the header value [rejected]. Should obtain
     * a fresh token for [url]'s server and return the new header value — or null if re-auth
     * failed. Issue #334: when several requests 401 at once, only the first should refresh;
     * the rest find the session already replaced (it no longer matches [rejected]) and reuse
     * the new token. Returning [rejected] itself means "nothing better right now": callers
     * then hand back the 401 instead of retrying with the same header.
     */
    fun refreshAuthHeader(url: Url, rejected: String? = null): String?
}

/** No-op default so the network module is usable before `:core:data` binds a real one. */
object NoAuthHeaderProvider : AuthHeaderProvider {
    override fun authHeader(url: Url): String? = null
    override fun refreshAuthHeader(url: Url, rejected: String?): String? = null
}
