package net.dexxicon.reader.core.data.sync

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.data.KOSYNC_CREDENTIAL_PROVIDER
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.kosync.KoSyncApi
import net.dexxicon.reader.core.serverapi.kosync.KoSyncProgressUpdate
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where to read bytes from to compute a book's KOReader digest. */
sealed interface DigestSource {
    data class LocalFile(val file: File) : DigestSource
    data class Remote(val url: String) : DigestSource
}

data class RemoteProgress(val percentage: Double, val timestamp: Long)

/**
 * Syncs reading position with a server's KOReader sync (`kosync`) endpoint so progress is
 * shared with the KOReader e-reader app and other devices. We only round-trip the
 * **percentage** — Readium and KOReader don't share a position format.
 */
@Singleton
class KoSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: KoSyncApi,
    private val credentialStore: CredentialStore,
    private val syncStateStore: SyncStateStore,
    @DexxiconHttpClient private val httpClient: OkHttpClient,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val digestCache = ConcurrentHashMap<String, String>()

    private val deviceName: String = android.os.Build.MODEL ?: "Android"
    private val deviceId: String by lazy {
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "dexxicon"
        MessageDigest.getInstance("MD5").digest("dexxicon:$androidId".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun isConfigured(server: Server): Boolean = !server.koSyncUsername.isNullOrBlank()

    private suspend fun authKey(server: Server): String? {
        val password = credentialStore.getSyncSecret(server.id, KOSYNC_CREDENTIAL_PROVIDER) ?: return null
        return MessageDigest.getInstance("MD5").digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Verify credentials against `{base}/users/auth`; a success also counts as "synced now". */
    suspend fun verify(server: Server): Boolean = withContext(io) {
        val base = server.effectiveKoSyncUrl
        val user = server.koSyncUsername ?: return@withContext false
        val key = authKey(server) ?: return@withContext false
        val ok = runCatching { api.authorize("$base/users/auth", user, key).isSuccessful }
            .getOrDefault(false)
        if (ok) syncStateStore.markSynced(server.id)
        ok
    }

    suspend fun pull(server: Server, cacheKey: String, source: DigestSource): RemoteProgress? =
        withContext(io) {
            if (!isConfigured(server)) return@withContext null
            val base = server.effectiveKoSyncUrl
            val user = server.koSyncUsername ?: return@withContext null
            val key = authKey(server) ?: return@withContext null
            val digest = digestFor(cacheKey, source) ?: return@withContext null
            runCatching {
                val response = api.getProgress("$base/syncs/progress/$digest", user, key)
                if (response.isSuccessful) syncStateStore.markSynced(server.id)
                val body = response.body()?.takeIf { response.isSuccessful } ?: return@runCatching null
                val pct = body.percentage ?: return@runCatching null
                RemoteProgress(pct, body.timestamp ?: 0L)
            }.getOrNull()
        }

    suspend fun push(server: Server, cacheKey: String, source: DigestSource, percentage: Double) =
        withContext(io) {
            if (!isConfigured(server)) return@withContext
            val base = server.effectiveKoSyncUrl
            val user = server.koSyncUsername ?: return@withContext
            val key = authKey(server) ?: return@withContext
            val digest = digestFor(cacheKey, source) ?: return@withContext
            runCatching {
                val response = api.putProgress(
                    url = "$base/syncs/progress",
                    user = user,
                    key = key,
                    body = KoSyncProgressUpdate(
                        document = digest,
                        progress = percentage.coerceIn(0.0, 1.0).toString(),
                        percentage = percentage.coerceIn(0.0, 1.0),
                        device = deviceName,
                        device_id = deviceId,
                    ),
                )
                if (response.isSuccessful) syncStateStore.markSynced(server.id)
            }
        }

    private suspend fun digestFor(cacheKey: String, source: DigestSource): String? {
        digestCache[cacheKey]?.let { return it }
        val readAt: suspend (Long, Int) -> ByteArray = when (source) {
            is DigestSource.LocalFile -> { offset, length -> readFromFile(source.file, offset, length) }
            is DigestSource.Remote -> { offset, length -> readFromRemote(source.url, offset, length) }
        }
        return runCatching { KoReaderDigest.compute(readAt) }.getOrNull()
            ?.also { digestCache[cacheKey] = it }
    }

    private fun readFromFile(file: File, offset: Long, length: Int): ByteArray {
        if (!file.exists() || offset >= file.length()) return ByteArray(0)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(minOf(length.toLong(), file.length() - offset).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = raf.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            return if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }

    private fun readFromRemote(url: String, offset: Long, length: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ByteArray(0)
            return response.body?.bytes() ?: ByteArray(0)
        }
    }
}
