package net.dexxicon.reader.core.security

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.CFBridgingRetain
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService

/**
 * iOS [actual]: stored directly in the Keychain via `multiplatform-settings`'
 * [KeychainSettings] — no app-level cipher needed (unlike the Android actual's
 * [CryptoStore]), since Keychain items are already encrypted at rest by the OS.
 *
 * Issue #334 — items are `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: device-only and
 * excluded from backups (the same property Android's storage gets from a non-exportable
 * AndroidKeyStore key), and readable/writable while the phone is locked, which background
 * work like an audiobook's progress sync needs to rotate a refresh token. `KeychainSettings`
 * sets no accessibility of its own, so items written before this change got iOS's default,
 * `WhenUnlocked`, which fails both reads and writes on a locked phone.
 *
 * `KeychainSettings` adds its properties to every query, lookups included, so [current] can't
 * see those older items; [any] (service only) matches both kinds. Each older item moves over
 * the first time it's read or written.
 *
 * `KeychainSettings` is marked `@ExperimentalSettingsImplementation` by the library itself
 * (its own API surface, not the underlying Keychain access, is what's still settling) — opted
 * into at this single call site rather than suppressed project-wide.
 */
@OptIn(ExperimentalSettingsImplementation::class, ExperimentalSettingsApi::class, ExperimentalForeignApi::class)
actual class CredentialStore {

    private val current = KeychainSettings(
        kSecAttrService to CFBridgingRetain(SERVICE), // lives as long as the app; never released
        kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    )
    private val any = KeychainSettings(service = SERVICE)

    actual suspend fun putPassword(serverId: String, password: String) =
        put(passwordKey(serverId), password)

    actual suspend fun getPassword(serverId: String): String? =
        get(passwordKey(serverId))

    actual suspend fun putRefreshToken(serverId: String, token: String) =
        put(refreshKey(serverId), token)

    actual suspend fun getRefreshToken(serverId: String): String? =
        get(refreshKey(serverId))

    actual suspend fun putSyncSecret(serverId: String, providerKey: String, secret: String) =
        put(syncKey(serverId, providerKey), secret)

    actual suspend fun getSyncSecret(serverId: String, providerKey: String): String? =
        get(syncKey(serverId, providerKey))

    actual suspend fun clear(serverId: String) {
        val prefix = "$serverId$DELIMITER"
        any.keys.filter { it.startsWith(prefix) }.forEach { any.remove(it) }
    }

    private fun get(key: String): String? =
        current.getStringOrNull(key) ?: any.getStringOrNull(key)?.also { migrate(key, it) }

    private fun put(key: String, value: String) {
        try {
            current.putString(key, value)
        } catch (_: IllegalStateException) {
            // An older item under the same key: the add is a duplicate and the update can't
            // find it under the new accessibility. Replace it.
            any.remove(key)
            current.putString(key, value)
        }
    }

    /** Best effort: the value was read, so a failed move just means trying again next time. */
    private fun migrate(key: String, value: String) {
        runCatching { put(key, value) }
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
