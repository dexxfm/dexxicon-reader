package net.dexxicon.reader.core.security

/**
 * Per-server secret storage. Implemented in `:core:data`... no — implemented right here,
 * per platform: Android encrypts (via [CryptoStore]) into DataStore, since SharedPreferences
 * /DataStore aren't encrypted at rest on their own; iOS stores directly in the Keychain,
 * which already is — no separate app-level cipher needed there. Consumed by
 * [AuthHeaderProvider][net.dexxicon.reader.core.network.AuthHeaderProvider]'s implementation
 * and the server/OIDC repositories in `:core:data`, which know about servers + credentials
 * but nothing about how they're actually stored.
 */
expect class CredentialStore {
    suspend fun putPassword(serverId: String, password: String)
    suspend fun getPassword(serverId: String): String?

    suspend fun putRefreshToken(serverId: String, token: String)
    suspend fun getRefreshToken(serverId: String): String?

    suspend fun putSyncSecret(serverId: String, providerKey: String, secret: String)
    suspend fun getSyncSecret(serverId: String, providerKey: String): String?

    suspend fun clear(serverId: String)
}
