package net.dexxicon.reader.core.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.database.entity.DownloadEntity
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Real, `WorkManager`-backed [DownloadRepository] — unchanged behavior from before this
 * interface existed; see that interface's doc comment for why the split. */
@Singleton
class AndroidDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val appPreferences: AppPreferencesStore,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) : DownloadRepository {
    override val supportsDownloads: Boolean = true

    private val workManager get() = WorkManager.getInstance(context)

    override val downloads: Flow<List<Download>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override val usedBytes: Flow<Long> =
        dao.observeAll().map { list -> list.sumOf { it.approxBytes() } }

    private fun DownloadEntity.approxBytes(): Long =
        totalBytes ?: downloadedBytes.takeIf { it > 0L } ?: 0L

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    override fun download(serverId: String, bookId: String): Flow<Download?> =
        dao.observe(key(serverId, bookId)).map { it?.toDomain() }

    override suspend fun get(serverId: String, bookId: String): Download? = withContext(io) {
        dao.find(key(serverId, bookId))?.toDomain()
    }

    override suspend fun enqueue(detail: BookDetail): EnqueueResult = withContext(io) {
        val prefs = appPreferences.preferences.first()
        val wifiOnly = prefs.downloadsWifiOnly
        val s = detail.summary
        val acquisition = detail.acquisitions.firstOrNull { it.format == s.format }
            ?: detail.primaryAcquisition
            ?: return@withContext EnqueueResult.NoFile

        val limit = prefs.downloadLimitBytes
        val needed = detail.fileSizeBytes ?: 0L
        if (limit != null && needed > 0L) {
            fun sizeOf(d: Download) = d.totalBytes ?: d.downloadedBytes.coerceAtLeast(0L)
            val all = downloads.first()
            // Don't count an existing copy of this same book against the limit (re-download).
            val alreadyHeld = all.firstOrNull { it.serverId == s.serverId && it.bookId == s.id }
                ?.let(::sizeOf) ?: 0L
            val projected = all.sumOf(::sizeOf) - alreadyHeld + needed
            if (projected > limit) {
                _messages.tryEmit(
                    "Not downloaded — it would exceed your ${formatGb(limit)} storage limit. " +
                        "Free up space or raise the limit in Settings.",
                )
                return@withContext EnqueueResult.OverLimit(
                    neededBytes = needed,
                    usedBytes = (all.sumOf(::sizeOf) - alreadyHeld).coerceAtLeast(0L),
                    limitBytes = limit,
                )
            }
        }

        val entity = DownloadEntity.new(
            serverId = s.serverId,
            bookId = s.id,
            title = s.title,
            authors = s.authors,
            series = s.series,
            coverUrl = s.coverUrl,
            format = s.format,
            sourceUrl = acquisition.href,
        )
        dao.upsert(entity)
        workManager.enqueueUniqueWork(
            DownloadWorker.workName(entity.key),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_KEY to entity.key))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
        EnqueueResult.Queued
    }

    override suspend fun remove(serverId: String, bookId: String) = withContext(io) {
        val key = key(serverId, bookId)
        workManager.cancelUniqueWork(DownloadWorker.workName(key))
        dao.find(key)?.localPath?.let { path ->
            File(path).delete()
            File("$path.part").delete()
        }
        dao.deleteByKey(key)
    }

    override suspend fun localFile(serverId: String, bookId: String): String? = withContext(io) {
        val entity = dao.find(key(serverId, bookId)) ?: return@withContext null
        if (entity.status != DownloadStatus.DONE.name) return@withContext null
        entity.localPath?.takeIf { File(it).exists() }
    }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 10 || gb == gb.toLong().toDouble()) "${gb.toLong()} GB" else "%.1f GB".format(gb)
    }
}
