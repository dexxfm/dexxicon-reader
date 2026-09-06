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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import kotlinx.coroutines.flow.first
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

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val appPreferences: AppPreferencesStore,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val workManager get() = WorkManager.getInstance(context)

    val downloads: Flow<List<Download>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun download(serverId: String, bookId: String): Flow<Download?> =
        dao.observe(key(serverId, bookId)).map { it?.toDomain() }

    suspend fun get(serverId: String, bookId: String): Download? = withContext(io) {
        dao.find(key(serverId, bookId))?.toDomain()
    }

    /** Queue (or re-queue) an offline copy of [detail]. */
    suspend fun enqueue(detail: BookDetail) = withContext(io) {
        val wifiOnly = appPreferences.preferences.first().downloadsWifiOnly
        val s = detail.summary
        val acquisition = detail.acquisitions.firstOrNull { it.format == s.format }
            ?: detail.primaryAcquisition
            ?: return@withContext
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
    }

    suspend fun remove(serverId: String, bookId: String) = withContext(io) {
        val key = key(serverId, bookId)
        workManager.cancelUniqueWork(DownloadWorker.workName(key))
        dao.find(key)?.localPath?.let { path ->
            File(path).delete()
            File("$path.part").delete()
        }
        dao.deleteByKey(key)
    }

    /** The on-disk file for a completed download, or null. */
    suspend fun localFile(serverId: String, bookId: String): File? = withContext(io) {
        val entity = dao.find(key(serverId, bookId)) ?: return@withContext null
        if (entity.status != DownloadStatus.DONE.name) return@withContext null
        entity.localPath?.let(::File)?.takeIf { it.exists() }
    }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"
}
