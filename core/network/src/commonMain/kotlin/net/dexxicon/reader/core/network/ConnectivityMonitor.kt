package net.dexxicon.reader.core.network

import kotlinx.coroutines.flow.Flow

enum class NetworkStatus { AVAILABLE, UNMETERED, UNAVAILABLE }

/**
 * - Android: backed by [ConnectivityManager][android.net.ConnectivityManager], unchanged
 *   from before this module went multiplatform.
 * - iOS: backed by `NWPathMonitor` (Network.framework).
 */
expect class ConnectivityMonitor {
    val status: Flow<NetworkStatus>
    fun currentStatus(): NetworkStatus
}
