package net.dexxicon.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.dexxicon.reader.core.common.currentTimeMillis
import okio.Path.Companion.toPath

/** Records when reading progress last round-tripped with each server's KOReader sync.
 *
 * Phase 4 restructure (issue #126) — moved to commonMain on DataStore's multiplatform
 * Preferences API. [syncStateFilePath] (expect, one `actual` per platform) supplies the one
 * genuinely platform-specific piece — where the preferences file lives — replacing the
 * Android-only `Context.preferencesDataStore` delegate this used before. `@Inject`/
 * `@Singleton` dropped; see `NativeProgressSync`'s (`:core:data`) doc comment for the
 * `:app`-hosted `@Provides` reasoning this follows.
 *
 * Stage C (issue #130) surfaced a real crash here: native's Hilt graph (`ProgressSyncModule`)
 * and `:shared`'s [net.dexxicon.reader.shared.di.AppContainer] each independently construct
 * their own `SyncStateStore` for the same file — one as soon as Home/Book Detail touch
 * `progressRepository`, the other whenever a Hilt-injected reader ViewModel is first created —
 * and DataStore hard-throws (`IllegalStateException`) if two `DataStore<Preferences>` instances
 * ever have the same file open at once. [dataStoreFor] memoizes by resolved path so every
 * `SyncStateStore` pointed at the same file — regardless of which composition root built it —
 * shares the one underlying `DataStore`.
 */
class SyncStateStore(context: PlatformStorageContext) {
    private val dataStore: DataStore<Preferences> = dataStoreFor(syncStateFilePath(context))

    private fun key(serverId: String) = longPreferencesKey("last_synced_$serverId")

    /** serverId → epoch millis of the last successful sync. */
    val lastSyncedAt: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        buildMap {
            prefs.asMap().forEach { (k, v) ->
                if (k.name.startsWith("last_synced_") && v is Long) {
                    put(k.name.removePrefix("last_synced_"), v)
                }
            }
        }
    }

    suspend fun markSynced(serverId: String, atMillis: Long = currentTimeMillis()) {
        dataStore.edit { it[key(serverId)] = atMillis }
    }

    private companion object {
        /** Every `SyncStateStore` construction in this process funnels through here, keyed by
         * resolved file path — see this class's doc comment. Construction is single-threaded
         * in practice (DI-graph startup on the main thread on both platforms), so a plain map
         * is enough; there's no genuinely concurrent access pattern to guard against here. */
        private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

        private fun dataStoreFor(path: String): DataStore<Preferences> =
            dataStores.getOrPut(path) {
                PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
            }
    }
}

/** Opaque per-platform handle [syncStateFilePath] needs to locate the preferences file —
 * an `android.content.Context` on Android, nothing on iOS (there's no equivalent object to
 * thread through; `NSHomeDirectory()` needs none). */
expect class PlatformStorageContext

expect fun syncStateFilePath(context: PlatformStorageContext): String
