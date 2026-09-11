package net.dexxicon.reader.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.network.DexxiconHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Serves book cover art to processes that can't carry our auth headers — chiefly Android
 * Auto / Automotive, which fetches `MediaMetadata.artworkUri` from its own process. The car
 * requests `content://<appId>.artwork/cover?src=<url>&sig=<hmac>`; we re-fetch that URL
 * here, in our process, through the authenticated OkHttp client and return a cached file.
 *
 * The provider is `exported` (the car host is a different app), so every request is HMAC-
 * signed with a per-install key that never leaves the app: an outside caller can't point it
 * at an arbitrary authenticated endpoint on one of the user's servers.
 */
class ArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HttpEntryPoint {
        @DexxiconHttpClient
        fun okHttpClient(): OkHttpClient
    }

    private val client: OkHttpClient by lazy {
        EntryPointAccessors.fromApplication(context(), HttpEntryPoint::class.java).okHttpClient()
    }

    private val cacheRoot: File by lazy {
        File(context().cacheDir, "auto-artwork").apply { mkdirs() }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        val src = uri.getQueryParameter(PARAM_SRC)?.takeIf { it.isNotBlank() } ?: return null
        val sig = uri.getQueryParameter(PARAM_SIG) ?: return null
        if (!constantTimeEquals(sig, sign(context(), src))) {
            Log.w(TAG, "rejected artwork request with a bad signature")
            return null
        }
        val file = fetch(src) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fetch(src: String): File? {
        val cached = File(cacheRoot, sha1(src) + extensionOf(src))
        if (cached.exists() && cached.length() > 0L) return cached
        return runCatching {
            client.newCall(Request.Builder().url(src).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "artwork fetch got HTTP ${resp.code} (session may need a re-sign-in)")
                    return null
                }
                val body = resp.body ?: return null
                val part = File(cacheRoot, cached.name + ".part")
                body.byteStream().use { input -> part.outputStream().use(input::copyTo) }
                if (!part.renameTo(cached)) {
                    part.copyTo(cached, overwrite = true)
                    part.delete()
                }
                cached
            }
        }.onFailure { Log.w(TAG, "artwork fetch failed", it) }.getOrNull()
    }

    // Read-only image provider — no table surface.
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    private fun context(): Context = requireNotNull(context) { "ArtworkProvider has no context" }

    companion object {
        private const val TAG = "ArtworkProvider"
        private const val PARAM_SRC = "src"
        private const val PARAM_SIG = "sig"
        private const val KEY_FILE = "auto-artwork.key"

        /**
         * A `content://` URI for [sourceUrl] that this provider will fetch through the authed
         * client. Signed with the per-install key so only in-app callers can mint one.
         */
        fun uriFor(context: Context, sourceUrl: String): Uri =
            "content://${context.packageName}.artwork/cover".toUri()
                .buildUpon()
                .appendQueryParameter(PARAM_SRC, sourceUrl)
                .appendQueryParameter(PARAM_SIG, sign(context, sourceUrl))
                .build()

        private fun sign(context: Context, value: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(installKey(context), "HmacSHA256"))
            return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        @Volatile
        private var cachedKey: ByteArray? = null

        @Synchronized
        private fun installKey(context: Context): ByteArray {
            cachedKey?.let { return it }
            val file = File(context.filesDir, KEY_FILE)
            val key = if (file.exists() && file.length() == 32L) {
                file.readBytes()
            } else {
                ByteArray(32).also { SecureRandom().nextBytes(it); file.writeBytes(it) }
            }
            cachedKey = key
            return key
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) result = result or (a[i].code xor b[i].code)
            return result == 0
        }

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun extensionOf(url: String): String {
            val last = runCatching { url.toUri().lastPathSegment }.getOrNull().orEmpty().lowercase()
            return when {
                last.endsWith(".png") -> ".png"
                last.endsWith(".webp") -> ".webp"
                last.endsWith(".gif") -> ".gif"
                else -> ".jpg"
            }
        }
    }
}
