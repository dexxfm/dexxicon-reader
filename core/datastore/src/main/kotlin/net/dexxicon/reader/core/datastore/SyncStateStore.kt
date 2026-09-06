package net.dexxicon.reader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStateDataStore: DataStore<Preferences> by preferencesDataStore("sync_state")

/** Records when reading progress last round-tripped with each server's KOReader sync. */
@Singleton
class SyncStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun key(serverId: String) = longPreferencesKey("last_synced_$serverId")

    /** serverId → epoch millis of the last successful sync. */
    val lastSyncedAt: Flow<Map<String, Long>> = context.syncStateDataStore.data.map { prefs ->
        buildMap {
            prefs.asMap().forEach { (k, v) ->
                if (k.name.startsWith("last_synced_") && v is Long) {
                    put(k.name.removePrefix("last_synced_"), v)
                }
            }
        }
    }

    suspend fun markSynced(serverId: String, atMillis: Long = System.currentTimeMillis()) {
        context.syncStateDataStore.edit { it[key(serverId)] = atMillis }
    }
}
