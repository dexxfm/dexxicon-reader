package net.dexxicon.reader.core.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.data.KOSYNC_CREDENTIAL_PROVIDER
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.kosync.KoSyncApi
import net.dexxicon.reader.core.serverapi.kosync.KoSyncProgressUpdate
import org.kotlincrypto.hash.md.MD5

/** Where to read bytes from to compute a book's KOReader digest. */
sealed interface DigestSource {
    /** [path] a local filesystem path — `java.io.File` itself isn't portable, so this is the
     * plain path string [readFileRange] takes. */
    data class LocalFile(val path: String) : DigestSource
    data class Remote(val url: String) : DigestSource
}

data class RemoteProgress(val percentage: Double, val timestamp: Long)

/**
 * Syncs reading position with a server's KOReader sync (`kosync`) endpoint so progress is
 * shared with the KOReader e-reader app and other devices. We only round-trip the
 * **percentage** — Readium and KOReader don't share a position format.
 *
 * Phase 4 restructure (issue #126) — moved to commonMain (the "full portability lift" over
 * stubbing this one Android-only, since BookOrbit/Grimmory aren't the only backends Book
 * Detail's progress row should work for on iOS too):
 *  - the raw HTTP range-GET for a remote digest sample now goes through the shared [HttpClient]
 *    instead of a raw `okhttp3.OkHttpClient` call;
 *  - MD5 (auth keys, [KoReaderDigest]'s partial-document hash) comes from
 *    `org.kotlincrypto.hash.md` instead of `java.security.MessageDigest`;
 *  - local-file digest sampling goes through [readFileRange] instead of
 *    `java.io.RandomAccessFile` directly;
 *  - [rawDeviceId]/[deviceModel] are passed in already-resolved rather than read from
 *    `Context`/`android.os.Build` here — both are one-time values the composition root
 *    (`:app`'s Hilt module, `:shared`'s `AppContainer`) can already compute with its own
 *    platform APIs, so no expect/actual is needed just to thread a `Context` through.
 *  `@Inject`/`@Singleton` dropped; see [NativeProgressSync]'s doc comment for the
 *  `:app`-hosted `@Provides` reasoning.
 */
class KoSyncRepository(
    private val api: KoSyncApi,
    private val credentialStore: CredentialStore,
    private val syncStateStore: SyncStateStore,
    private val httpClient: HttpClient,
    private val io: CoroutineDispatcher,
    rawDeviceId: String,
    private val deviceModel: String,
) {
    private val digestCacheMutex = Mutex()
    private val digestCache = mutableMapOf<String, String>()

    private val deviceId: String =
        MD5().let { it.update("dexxicon:$rawDeviceId".encodeToByteArray()); it.digest() }.toHex()

    fun isConfigured(server: Server): Boolean = !server.koSyncUsername.isNullOrBlank()

    private suspend fun authKey(server: Server): String? {
        val password = credentialStore.getSyncSecret(server.id, KOSYNC_CREDENTIAL_PROVIDER) ?: return null
        return MD5().let { it.update(password.encodeToByteArray()); it.digest() }.toHex()
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
                        device = deviceModel,
                        device_id = deviceId,
                    ),
                )
                if (response.isSuccessful) syncStateStore.markSynced(server.id)
            }
        }

    private suspend fun digestFor(cacheKey: String, source: DigestSource): String? {
        digestCacheMutex.withLock { digestCache[cacheKey] }?.let { return it }
        val readAt: suspend (Long, Int) -> ByteArray = when (source) {
            is DigestSource.LocalFile -> { offset, length -> readFileRange(source.path, offset, length) }
            is DigestSource.Remote -> { offset, length -> readFromRemote(source.url, offset, length) }
        }
        return runCatching { KoReaderDigest.compute(readAt) }.getOrNull()
            ?.also { digest -> digestCacheMutex.withLock { digestCache[cacheKey] = digest } }
    }

    private suspend fun readFromRemote(url: String, offset: Long, length: Int): ByteArray =
        runCatching {
            val response = httpClient.get(url) {
                header(HttpHeaders.Range, "bytes=$offset-${offset + length - 1}")
            }
            if (!response.status.isSuccess()) return@runCatching ByteArray(0)
            response.body<ByteArray>()
        }.getOrDefault(ByteArray(0))
}
