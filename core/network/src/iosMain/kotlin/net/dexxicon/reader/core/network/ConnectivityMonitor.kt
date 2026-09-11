package net.dexxicon.reader.core.network

import dev.jordond.connectivity.Connectivity
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * iOS actual, backed by the `dev.jordond.connectivity` library's native Apple monitor
 * (Network.framework under the hood) rather than hand-written Kotlin/Native interop —
 * `NWPathMonitor` itself isn't reachable directly from Kotlin/Native (Swift-only surface,
 * same class of problem [CredentialStore][net.dexxicon.reader.core.security.CredentialStore]
 * hit with CryptoKit), and the older C-based `SCNetworkReachability` API needs its own
 * C-callback interop that's just as unverifiable locally as that would have been. A mature,
 * widely-used library doing the same job removes that risk instead of adding to it.
 *
 * [currentStatus] reads a cached value kept fresh by an internal collector, rather than
 * blocking on the library's own suspend `status()` — keeping this call synchronous, matching
 * the Android actual (and the `expect` contract), without ever blocking a caller's thread.
 */
actual class ConnectivityMonitor {
    private val connectivity = Connectivity().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var latest: NetworkStatus = NetworkStatus.UNAVAILABLE

    actual val status: Flow<NetworkStatus> = connectivity.statusUpdates
        .map { it.toNetworkStatus() }
        .onEach { latest = it }

    actual fun currentStatus(): NetworkStatus = latest

    init {
        // Keeps `status` hot so its `onEach { latest = it }` side effect actually runs — the
        // update itself happens upstream, this collector just needs to exist. A bare
        // `.collect()` (no argument) was never valid: every collect() overload takes either a
        // FlowCollector or an action lambda.
        scope.launch { status.collect {} }
    }

    private fun Connectivity.Status.toNetworkStatus(): NetworkStatus = when (this) {
        is Connectivity.Status.Connected -> if (metered) NetworkStatus.AVAILABLE else NetworkStatus.UNMETERED
        Connectivity.Status.Disconnected -> NetworkStatus.UNAVAILABLE
    }
}
