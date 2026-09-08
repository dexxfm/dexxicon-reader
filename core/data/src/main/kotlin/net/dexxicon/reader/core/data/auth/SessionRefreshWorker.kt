package net.dexxicon.reader.core.data.auth

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import net.dexxicon.reader.core.data.ServerRepository
import java.util.concurrent.TimeUnit

/**
 * Keeps every server's session warm in the background. A phone left untouched for a day
 * still has a live access token when it's next used — and, crucially, each OIDC refresh
 * token is exercised well before the IdP would expire an idle one and leave a manual
 * re-sign-in as the only way back.
 */
@HiltWorker
class SessionRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val serverRepository: ServerRepository,
    private val tokenManager: TokenManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching {
            serverRepository.servers.first().forEach { server ->
                runCatching { tokenManager.refreshIfStale(server) }
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "session-refresh"

        /** Enqueue the periodic refresh; safe to call on every app start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SessionRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
