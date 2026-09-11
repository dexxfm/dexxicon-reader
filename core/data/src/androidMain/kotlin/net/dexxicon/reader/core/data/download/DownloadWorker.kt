package net.dexxicon.reader.core.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.database.entity.DownloadEntity
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.fileExtension
import net.dexxicon.reader.core.network.DexxiconHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @DexxiconHttpClient private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val key = inputData.getString(KEY_DOWNLOAD_KEY) ?: return@withContext Result.failure()
        val entity = downloadDao.find(key) ?: return@withContext Result.failure()

        val format = runCatching { ContentFormat.valueOf(entity.format) }
            .getOrDefault(ContentFormat.UNKNOWN)
        val dir = File(applicationContext.filesDir, "library/${entity.serverId}").apply { mkdirs() }
        val target = File(dir, "${entity.bookId}.${format.fileExtension}")
        val partial = File(dir, "${target.name}.part")

        try {
            mark(key, DownloadStatus.RUNNING, 0, null, null, null)
            runCatching { setForeground(foregroundInfo()) }

            val response = client.newCall(Request.Builder().url(entity.sourceUrl).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext failWith(key, partial, "Server returned HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext failWith(key, partial, "Empty response")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            if (isStopped) return@withContext failWith(key, partial, null)
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= REPORT_EVERY_BYTES) {
                                mark(key, DownloadStatus.RUNNING, downloaded, total, null, null)
                                runCatching { setForeground(foregroundInfo()) }
                                lastReported = downloaded
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            mark(
                key, DownloadStatus.DONE,
                target.length(), target.length(), target.absolutePath, null,
            )
            Result.success()
        } catch (e: Exception) {
            failWith(key, partial, e.message ?: "Download failed")
        }
    }

    private suspend fun failWith(key: String, partial: File, message: String?): Result {
        partial.delete()
        if (message == null) {
            // Cancelled — leave the row queued so the user can retry.
            mark(key, DownloadStatus.QUEUED, 0, null, null, null)
            return Result.failure()
        }
        mark(key, DownloadStatus.FAILED, 0, null, null, message)
        return Result.failure()
    }

    private suspend fun mark(
        key: String,
        status: DownloadStatus,
        downloaded: Long,
        total: Long?,
        localPath: String?,
        error: String?,
    ) = downloadDao.updateState(
        key = key,
        status = status.name,
        downloaded = downloaded,
        total = total,
        localPath = localPath,
        error = error,
        updatedAt = System.currentTimeMillis(),
    )

    /**
     * One notification for **all** in-flight downloads, built from the shared DB state so
     * every concurrent worker posts the same content (no flip-flopping between books) and
     * the rows keep a fixed order. Lists up to [MAX_TILES] at once.
     */
    private suspend fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }

        val active = runCatching { downloadDao.activeDownloads() }.getOrDefault(emptyList())
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)

        when {
            active.size <= 1 -> {
                val one = active.firstOrNull()
                builder.setContentTitle(one?.let { "Downloading ${it.title}" } ?: "Preparing download…")
                val pct = one?.percent()
                if (pct != null) builder.setContentText("$pct%")
                builder.setProgress(100, pct ?: 0, pct == null)
            }
            else -> {
                val shown = active.take(MAX_TILES)
                val lines = buildList {
                    shown.forEach { add("${it.title}  ·  ${it.percentLabel()}") }
                    if (active.size > shown.size) add("+${active.size - shown.size} more")
                }
                builder.setContentTitle("Downloading ${active.size} books")
                    .setContentText(lines.first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                val totals = active.mapNotNull { it.totalBytes }
                if (totals.size == active.size && totals.sum() > 0L) {
                    val got = active.sumOf { it.downloadedBytes }
                    builder.setProgress(100, (got * 100 / totals.sum()).toInt(), false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }
        }

        val notification: Notification = builder.build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Whole-number percent, or null when the size isn't known yet. */
    private fun DownloadEntity.percent(): Int? =
        totalBytes?.takeIf { it > 0 }?.let { ((downloadedBytes * 100) / it).toInt().coerceIn(0, 100) }

    private fun DownloadEntity.percentLabel(): String = when {
        status == DownloadStatus.QUEUED.name -> "waiting"
        percent() != null -> "${percent()}%"
        else -> "starting…"
    }

    companion object {
        const val KEY_DOWNLOAD_KEY = "download_key"
        fun workName(key: String) = "download:$key"

        private const val CHANNEL_ID = "dexxicon.downloads"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_TILES = 5
        private const val REPORT_EVERY_BYTES = 1024L * 1024
    }
}
