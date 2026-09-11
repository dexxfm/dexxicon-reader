package net.dexxicon.reader.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
actual class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    actual val status: Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.toStatus())
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.UNAVAILABLE)
            }
        }

        trySend(currentStatus())
        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()

    actual fun currentStatus(): NetworkStatus {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return NetworkStatus.UNAVAILABLE
        return caps.toStatus()
    }

    private fun NetworkCapabilities.toStatus(): NetworkStatus = when {
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkStatus.UNAVAILABLE
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkStatus.UNMETERED
        else -> NetworkStatus.AVAILABLE
    }
}
