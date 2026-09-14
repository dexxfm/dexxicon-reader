package net.dexxicon.reader.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [ReauthCoordinator] carries a "re-authenticate this server" request from a notification tap
 * ([net.dexxicon.reader.MainActivity]) into [AppShellViewModel.reauthRequests], which the
 * in-app [net.dexxicon.reader.ui.DexxiconApp] and `SignInBanner` both act on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReauthCoordinatorTest {

    @Test
    fun `request delivers the server id to an active collector`() = runTest {
        val coordinator = ReauthCoordinator()
        val received = mutableListOf<String>()
        // backgroundScope's collector starts on this same test dispatcher, so it's actively
        // subscribed before the next line runs — a real collector on a real dispatcher could
        // instead start collecting after request() already fired, since `requests` has no
        // replay (matching that real race, not papering over it with an artificial delay).
        val job = backgroundScope.launch { coordinator.requests.collect { received.add(it) } }
        runCurrent()

        coordinator.request("server-1")
        runCurrent()

        assertThat(received).containsExactly("server-1")
        job.cancel()
    }

    @Test
    fun `multiple requests are delivered in order`() = runTest {
        val coordinator = ReauthCoordinator()
        val received = mutableListOf<String>()
        val job = backgroundScope.launch { coordinator.requests.collect { received.add(it) } }
        runCurrent()

        coordinator.request("server-1")
        coordinator.request("server-2")
        coordinator.request("server-3")
        runCurrent()

        assertThat(received).containsExactly("server-1", "server-2", "server-3").inOrder()
        job.cancel()
    }

    @Test
    fun `a request with no collector is dropped, not queued forever`() = runTest {
        val coordinator = ReauthCoordinator()
        // No collector yet — requests has no replay, so this is simply lost, same as it would
        // be for a real notification tap that arrives before the UI starts observing.
        coordinator.request("server-1")

        val received = mutableListOf<String>()
        val job = backgroundScope.launch { coordinator.requests.toList(received) }
        runCurrent()

        coordinator.request("server-2")
        runCurrent()

        assertThat(received).containsExactly("server-2")
        job.cancel()
    }
}
