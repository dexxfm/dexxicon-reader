package net.dexxicon.reader.core.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.credentialDataStore by preferencesDataStore(name = "dexxicon_credentials")

/**
 * Android [actual]: values are AES-GCM encrypted by [CryptoStore] before hitting disk; the
 * DataStore only ever holds ciphertext (unlike the iOS actual, which needs no separate
 * cipher — see the [expect] declaration).
 */
@Singleton
actual class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoStore: CryptoStore,
) {

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
        context.credentialDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith("$serverId$DELIMITER") }
                .forEach { prefs.remove(it) }
        }
    }

    private suspend fun put(key: String, value: String) {
        val encrypted = cryptoStore.encrypt(value)
        context.credentialDataStore.edit { it[stringPreferencesKey(key)] = encrypted }
    }

    private suspend fun get(key: String): String? {
        val encrypted = context.credentialDataStore.data
            .map { it[stringPreferencesKey(key)] }
            .first() ?: return null
        return runCatching { cryptoStore.decrypt(encrypted) }.getOrNull()
    }

    private fun passwordKey(serverId: String) = "$serverId${DELIMITER}password"
    private fun refreshKey(serverId: String) = "$serverId${DELIMITER}refresh"
    private fun syncKey(serverId: String, providerKey: String) =
        "$serverId${DELIMITER}sync${DELIMITER}$providerKey"

    private companion object {
        const val DELIMITER = "::"
    }
}
