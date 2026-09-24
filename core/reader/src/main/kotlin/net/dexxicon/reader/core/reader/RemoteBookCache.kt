package net.dexxicon.reader.core.reader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.network.DexxiconHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * issue #291 — whole-file copies of books an OPDS catalog serves as open-access downloads.
 * Readium streams a remote EPUB through ranged ZIP reads, which stalled on Project Gutenberg's
 * illustrated EPUBs (~25 MB, served from a CDN after a redirect): each range came back as most of
 * the file, and the page stayed blank. The iOS reader already downloads EPUBs in full for its own
 * reasons (`RemoteFileCache.swift`); this is the Android equivalent, used only for OPDS files.
 * Cached by URL in the app's cache dir, which the OS may clear.
 */
@Singleton
class RemoteBookCache @Inject constructor(
    @ApplicationContext private val context: Context,
    @DexxiconHttpClient private val httpClient: OkHttpClient,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val cacheDir: File by lazy { File(context.cacheDir, "remote-books").apply { mkdirs() } }

    /** [url] downloaded in full (or the copy already here), named with [extension]. */
    suspend fun fetch(url: String, extension: String): File = withContext(io) {
        val out = File(cacheDir, "${sha1(url)}.$extension")
        if (out.exists() && out.length() > 0L) return@withContext out
        val tmp = File.createTempFile("book", ".part", cacheDir)
        try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} fetching the book" }
                val body = response.body ?: error("empty book response")
                tmp.outputStream().use { sink -> body.byteStream().use { it.copyTo(sink) } }
            }
            check(tmp.renameTo(out)) { "couldn't cache the book" }
            out
        } finally {
            tmp.delete()
        }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
