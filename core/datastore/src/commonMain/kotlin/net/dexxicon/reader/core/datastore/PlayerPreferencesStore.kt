package net.dexxicon.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/** The speeds offered anywhere playback speed is picked — the player's own sheet and Settings. */
val PLAYBACK_SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f, 3.0f)

/** Audiobook playback options, persisted across sessions. */
data class PlayerPreferences(
    /** Trim long silences from speech (ExoPlayer skip-silence). */
    val skipSilence: Boolean = false,
    /** Starting speed for every audiobook you open; changing it anywhere updates this too. */
    val defaultSpeed: Float = 1f,
    /** Seconds the "skip forward" control jumps. */
    val skipForwardSeconds: Int = 30,
    /** Seconds the "skip back" control jumps. */
    val skipBackSeconds: Int = 15,
    /** On resume, rewind this many seconds to recover context (0 = off). */
    val smartRewindSeconds: Int = 0,
)

/**
 * Phase 4 Stage H (issue #145) — moved to commonMain following [AppPreferencesStore]'s
 * precedent: `@Inject`/`@Singleton` dropped (an explicit `@Provides` in `:app`'s `DataModule`
 * now supplies it to Hilt), the old Android-only `Context.preferencesDataStore` delegate
 * replaced with [playerPreferencesFilePath] (expect, one `actual` per platform) plus the same
 * [dataStoreFor] memoization [SyncStateStore]/[AppPreferencesStore] use.
 */
class PlayerPreferencesStore(context: PlatformStorageContext) {
    private val dataStore: DataStore<Preferences> = dataStoreFor(playerPreferencesFilePath(context))

    private object Keys {
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val SKIP_FWD = intPreferencesKey("skip_forward_s")
        val SKIP_BACK = intPreferencesKey("skip_back_s")
        val SMART_REWIND = intPreferencesKey("smart_rewind_s")
    }

    val preferences: Flow<PlayerPreferences> = dataStore.data.map { p ->
        PlayerPreferences(
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            defaultSpeed = p[Keys.DEFAULT_SPEED] ?: 1f,
            skipForwardSeconds = p[Keys.SKIP_FWD] ?: 30,
            skipBackSeconds = p[Keys.SKIP_BACK] ?: 15,
            smartRewindSeconds = p[Keys.SMART_REWIND] ?: 0,
        )
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        dataStore.edit { it[Keys.SKIP_SILENCE] = enabled }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        dataStore.edit { it[Keys.DEFAULT_SPEED] = speed }
    }

    suspend fun setSkipForwardSeconds(seconds: Int) {
        dataStore.edit { it[Keys.SKIP_FWD] = seconds }
    }

    suspend fun setSkipBackSeconds(seconds: Int) {
        dataStore.edit { it[Keys.SKIP_BACK] = seconds }
    }

    suspend fun setSmartRewindSeconds(seconds: Int) {
        dataStore.edit { it[Keys.SMART_REWIND] = seconds }
    }

    private companion object {
        private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

        private fun dataStoreFor(path: String): DataStore<Preferences> =
            dataStores.getOrPut(path) {
                PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
            }
    }
}

/** Opaque per-platform handle [playerPreferencesFilePath] needs to locate the preferences
 * file — see [PlatformStorageContext] (shared with [SyncStateStore]/[AppPreferencesStore]). */
expect fun playerPreferencesFilePath(context: PlatformStorageContext): String
