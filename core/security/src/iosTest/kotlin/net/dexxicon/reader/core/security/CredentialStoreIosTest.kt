package net.dexxicon.reader.core.security

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * issue #334 — Grimmory rotates the refresh token on every refresh, so the stored one is
 * overwritten again and again. Each write has to replace the previous value in the Keychain;
 * if one throws or leaves the old token behind, the next refresh sends a revoked token.
 * Runs on the iOS simulator against the real Keychain.
 */
class CredentialStoreIosTest {

    private val store = CredentialStore()
    private val serverId = "test-334-${kotlin.random.Random.nextInt(1_000_000)}"

    @AfterTest
    fun cleanUp() = runTest { store.clear(serverId) }

    @Test
    fun aRotatedRefreshTokenReplacesTheStoredOne() = runTest {
        store.putRefreshToken(serverId, "first")
        assertEquals("first", store.getRefreshToken(serverId))

        store.putRefreshToken(serverId, "second")
        assertEquals("second", store.getRefreshToken(serverId))

        store.putRefreshToken(serverId, "third")
        assertEquals("third", store.getRefreshToken(serverId))
    }

    @Test
    fun clearRemovesTheServersSecrets() = runTest {
        store.putPassword(serverId, "pw")
        store.putRefreshToken(serverId, "rt")
        store.clear(serverId)
        assertNull(store.getPassword(serverId))
        assertNull(store.getRefreshToken(serverId))
    }
}
