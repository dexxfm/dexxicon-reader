package net.dexxicon.reader.core.reader

import android.content.Context
import com.github.junrar.Archive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Readium's archive support is ZIP-only, so it can open CBZ but not CBR (RAR) or CB7 (7z).
 * This normalizes a comic archive to a CBZ the navigator can read: ZIP inputs pass straight
 * through; RAR inputs are unpacked (junrar) and repacked as a stored ZIP, cached by content
 * hash. RAR can't be range-streamed, so remote CBRs are fetched in full first.
 */
@Singleton
class ComicArchiveNormalizer @Inject constructor(
    @ApplicationContext private val context: Context,
    @DexxiconHttpClient private val httpClient: OkHttpClient,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val cacheDir: File by lazy { File(context.cacheDir, "comic-cbz").apply { mkdirs() } }

    /** True when [href]/[mediaType] name a RAR-based comic archive. */
    fun looksLikeRar(href: String?, mediaType: String?): Boolean {
        val h = href?.substringBefore('?')?.lowercase().orEmpty()
        val m = mediaType?.lowercase().orEmpty()
        return h.endsWith(".cbr") || h.endsWith(".rar") || "rar" in m
    }

    /** Return a ZIP-based CBZ for [file]; the same file if it is already a ZIP. */
    suspend fun fromFile(file: File): File = withContext(io) {
        if (!isRar(file)) return@withContext file
        val out = File(cacheDir, "${sha1(file)}.cbz")
        if (!out.exists() || out.length() == 0L) rarToCbz(file, out)
        out
    }

    /** Download [url] in full and return a ZIP-based CBZ for it. */
    suspend fun fromUrl(url: String): File = withContext(io) {
        val tmp = File(cacheDir, "dl-${sha1(url.toByteArray())}.bin")
        if (!tmp.exists() || tmp.length() == 0L) {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} fetching comic" }
                val body = response.body ?: error("empty comic response")
                tmp.outputStream().use { sink -> body.byteStream().use { it.copyTo(sink) } }
            }
        }
        val normalized = fromFile(tmp)
        if (normalized != tmp) tmp.delete()
        normalized
    }

    private fun isRar(file: File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        val head = ByteArray(8)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        // "Rar!\x1A\x07" — RAR4 has 0x00 next, RAR5 has 0x01; both start the same.
        return head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
            head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte() &&
            head[4] == 0x1A.toByte() && head[5] == 0x07.toByte()
    }

    private fun rarToCbz(rar: File, out: File) {
        val partial = File(out.parentFile, out.name + ".part")
        try {
            Archive(rar).use { archive ->
                ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                    zip.setMethod(ZipOutputStream.DEFLATED)
                    zip.setLevel(Deflater.NO_COMPRESSION) // images don't compress; keep it fast
                    var header = archive.nextFileHeader()
                    while (header != null) {
                        if (!header.isDirectory) {
                            val name = header.fileName.replace('\\', '/')
                            if (name.hasImageExtension()) {
                                zip.putNextEntry(ZipEntry(name))
                                archive.extractFile(header, zip)
                                zip.closeEntry()
                            }
                        }
                        header = archive.nextFileHeader()
                    }
                }
            }
            if (!partial.renameTo(out)) {
                partial.copyTo(out, overwrite = true)
                partial.delete()
            }
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    private fun String.hasImageExtension(): Boolean {
        val lower = substringAfterLast('.').lowercase()
        return lower in IMAGE_EXTENSIONS
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jxl")
    }
}
