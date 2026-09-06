package net.dexxicon.reader.core.network

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import net.dexxicon.reader.core.security.CryptoStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [CookieJar] that survives process death, encrypted at rest.
 *
 * Needed because BookOrbit issues its refresh token as an `HttpOnly` `refresh_token`
 * cookie (scoped to `/api/v1/auth`) rather than in the login/OIDC response body — without
 * a persistent jar the session dies when the 15-minute access token expires.
 *
 * TAB separates cookie fields and NEWLINE separates cookie records; both are illegal in
 * cookie octets (RFC 6265) so they never collide with real data.
 */
@Singleton
class PersistentCookieJar @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: CryptoStore,
) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dexxicon_cookies_v1", Context.MODE_PRIVATE)

    /** host -> cookies */
    private val cache = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        prefs.all.forEach { (host, value) ->
            val blob = (value as? String)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
                ?: return@forEach
            val cookies = blob.split('\n')
                .mapNotNull { deserialize(it) }
                .toMutableList()
            if (cookies.isNotEmpty()) cache[host] = cookies
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val list = cache.getOrPut(host) { mutableListOf() }
        cookies.forEach { cookie ->
            list.removeAll { it.name == cookie.name && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) list.add(cookie)
        }
        persist(host, list)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val list = cache[url.host] ?: return emptyList()
        val valid = list.filter { it.expiresAt > now }
        if (valid.size != list.size) {
            list.clear()
            list.addAll(valid)
            persist(url.host, list)
        }
        return valid.filter { it.matches(url) }
    }

    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    private fun persist(host: String, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            prefs.edit().remove(host).apply()
            return
        }
        val blob = cookies.joinToString("\n") { serialize(it) }
        prefs.edit().putString(host, crypto.encrypt(blob)).apply()
    }

    private fun serialize(c: Cookie): String = listOf(
        c.name, c.value, c.expiresAt.toString(), c.domain, c.path,
        c.secure.toString(), c.httpOnly.toString(), c.hostOnly.toString(),
    ).joinToString("\t")

    private fun deserialize(raw: String): Cookie? {
        val p = raw.split('\t')
        if (p.size != 8) return null
        return try {
            Cookie.Builder()
                .name(p[0])
                .value(p[1])
                .expiresAt(p[2].toLong())
                .path(p[4])
                .apply {
                    if (p[7].toBoolean()) hostOnlyDomain(p[3]) else domain(p[3])
                    if (p[5].toBoolean()) secure()
                    if (p[6].toBoolean()) httpOnly()
                }
                .build()
        } catch (e: Exception) {
            null
        }
    }
}
