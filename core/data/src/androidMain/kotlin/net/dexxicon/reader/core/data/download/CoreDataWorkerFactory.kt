package net.dexxicon.reader.core.data.download

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.SessionRefreshWorker
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.network.DexxiconHttpClient
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-built [WorkerFactory] for this module's two background workers ([DownloadWorker],
 * [SessionRefreshWorker]) — `androidx.hilt:hilt-compiler`'s `@HiltWorker` codegen never runs
 * for either, because both live in `:core:data`'s `androidMain`, a KMP source set the Hilt
 * Gradle plugin refuses to apply to (see this module's `build.gradle.kts`'s own doc comment).
 * `HiltWorkerFactory` silently returns null for a class it has no generated binding for, so
 * `WorkManager` fell back to a plain no-args reflection constructor that doesn't exist —
 * every enqueue failed instantly with `NoSuchMethodException`, which is issue #143 (downloads
 * queued but never actually downloading; session refresh was equally broken, just unnoticed).
 *
 * Registered directly in `DexxiconApplication.workManagerConfiguration` in place of
 * `HiltWorkerFactory` — there are no other `@HiltWorker` classes left in the codebase to
 * support, so nothing is lost by not delegating to it.
 */
@Singleton
class CoreDataWorkerFactory @Inject constructor(
    @DexxiconHttpClient private val client: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val serverRepository: ServerRepository,
    private val tokenManager: TokenManager,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        DownloadWorker::class.java.name ->
            DownloadWorker(appContext, workerParameters, client, downloadDao)
        SessionRefreshWorker::class.java.name ->
            SessionRefreshWorker(appContext, workerParameters, serverRepository, tokenManager)
        else -> null
    }
}
