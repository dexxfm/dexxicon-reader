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

    /**
     * Issue #337 — suspending forms of the two above, for Ktor's (suspending) plugin. The
     * blocking forms exist for OkHttp's synchronous interceptor; called from a coroutine they
     * pin a thread in `runBlocking` for as long as a token refresh takes and can't be
     * cancelled, so a timeout around an unreachable server's request didn't actually end it.
     * Forwarding wrappers must forward these too, or they fall back to the blocking forms.
     */
    suspend fun awaitAuthHeader(url: Url): String? = authHeader(url)

    suspend fun awaitRefreshedAuthHeader(url: Url, rejected: String?): String? = refreshAuthHeader(url, rejected)
}

/** No-op default so the network module is usable before `:core:data` binds a real one. */
object NoAuthHeaderProvider : AuthHeaderProvider {
    override fun authHeader(url: Url): String? = null
    override fun refreshAuthHeader(url: Url, rejected: String?): String? = null
}
