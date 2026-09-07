package net.dexxicon.reader.core.data.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.ServerRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors [TokenManager.needsSignIn] into a persistent notification per affected server, so
 * an OIDC session that expired while the app was closed is still recoverable — the tap
 * deep-links straight back into that server's sign-in. Cleared automatically once the
 * session is restored.
 */
@Singleton
class SignInNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverRepository: ServerRepository,
    tokenManager: TokenManager,
    @ApplicationScope scope: CoroutineScope,
) {
    private val shown = mutableSetOf<String>()

    init {
        scope.launch {
            tokenManager.needsSignIn.collectLatest { ids -> sync(ids) }
        }
    }

    private suspend fun sync(ids: Set<String>) {
        val manager = NotificationManagerCompat.from(context)
        (shown - ids).forEach { manager.cancel(notificationId(it)) }
        if (ids.isNotEmpty()) ensureChannel()
        (ids - shown).forEach { id ->
            val name = serverRepository.get(id)?.displayName ?: "your library"
            runCatching { manager.notify(notificationId(id), build(id, name)) }
        }
        shown.clear()
        shown.addAll(ids)
    }

    private fun build(serverId: String, displayName: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Sign in to $displayName")
            .setContentText("Its session expired — tap to sign in again.")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(reauthIntent(serverId))
            .build()

    private fun reauthIntent(serverId: String): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                putExtra(EXTRA_REAUTH_SERVER, serverId)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        return PendingIntent.getActivity(
            context,
            notificationId(serverId),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Sign-in required",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun notificationId(serverId: String): Int =
        NOTIFICATION_ID_BASE + (serverId.hashCode() and 0xFFFF)

    companion object {
        /** Extra on the launch intent naming the server to re-authenticate. */
        const val EXTRA_REAUTH_SERVER = "net.dexxicon.reader.REAUTH_SERVER"

        private const val CHANNEL_ID = "dexxicon.auth"
        private const val NOTIFICATION_ID_BASE = 5100
    }
}
