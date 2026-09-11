package net.dexxicon.reader.core.security

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

/**
 * iOS [actual]: stored directly in the Keychain via `multiplatform-settings`'
 * [KeychainSettings] — no app-level cipher needed (unlike the Android actual's
 * [CryptoStore]), since Keychain items are already encrypted at rest by the OS. Each item
 * gets `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` by that library's own default
 * (device-only, excluded from backups) — the same property Android's DataStore-backed
 * storage gets from a non-exportable AndroidKeyStore key.
 *
 * `KeychainSettings` is marked `@ExperimentalSettingsImplementation` by the library itself
 * (its own API surface, not the underlying Keychain access, is what's still settling) — opted
 * into at this single call site rather than suppressed project-wide.
 */
@OptIn(ExperimentalSettingsImplementation::class)
actual class CredentialStore {

    private val settings: Settings = KeychainSettings(service = SERVICE)

    actual suspend fun putPassword(serverId: String, password: String) =
        settings.putString(passwordKey(serverId), password)

    actual suspend fun getPassword(serverId: String): String? =
        settings.getStringOrNull(passwordKey(serverId))

    actual suspend fun putRefreshToken(serverId: String, token: String) =
        settings.putString(refreshKey(serverId), token)

    actual suspend fun getRefreshToken(serverId: String): String? =
        settings.getStringOrNull(refreshKey(serverId))

    actual suspend fun putSyncSecret(serverId: String, providerKey: String, secret: String) =
        settings.putString(syncKey(serverId, providerKey), secret)

    actual suspend fun getSyncSecret(serverId: String, providerKey: String): String? =
        settings.getStringOrNull(syncKey(serverId, providerKey))

    actual suspend fun clear(serverId: String) {
        val prefix = "$serverId$DELIMITER"
        settings.keys.filter { it.startsWith(prefix) }.forEach { settings.remove(it) }
    }

    private fun passwordKey(serverId: String) = "$serverId${DELIMITER}password"
    private fun refreshKey(serverId: String) = "$serverId${DELIMITER}refresh"
    private fun syncKey(serverId: String, providerKey: String) =
        "$serverId${DELIMITER}sync${DELIMITER}$providerKey"

    private companion object {
        const val SERVICE = "net.dexxicon.reader.credentials"
        const val DELIMITER = "::"
    }
}
