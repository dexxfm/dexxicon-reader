package net.dexxicon.reader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Audiobook playback options, persisted across sessions. */
data class PlayerPreferences(
    /** Trim long silences from speech (ExoPlayer skip-silence). */
    val skipSilence: Boolean = false,
    /** Seconds the "skip forward" control jumps. */
    val skipForwardSeconds: Int = 30,
    /** Seconds the "skip back" control jumps. */
    val skipBackSeconds: Int = 15,
    /** On resume, rewind this many seconds to recover context (0 = off). */
    val smartRewindSeconds: Int = 0,
)

private val Context.playerPrefsDataStore: DataStore<Preferences> by preferencesDataStore("player_prefs")

@Singleton
class PlayerPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val SKIP_FWD = intPreferencesKey("skip_forward_s")
        val SKIP_BACK = intPreferencesKey("skip_back_s")
        val SMART_REWIND = intPreferencesKey("smart_rewind_s")
    }

    val preferences: Flow<PlayerPreferences> = context.playerPrefsDataStore.data.map { p ->
        PlayerPreferences(
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            skipForwardSeconds = p[Keys.SKIP_FWD] ?: 30,
            skipBackSeconds = p[Keys.SKIP_BACK] ?: 15,
            smartRewindSeconds = p[Keys.SMART_REWIND] ?: 0,
        )
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        context.playerPrefsDataStore.edit { it[Keys.SKIP_SILENCE] = enabled }
    }

    suspend fun setSkipForwardSeconds(seconds: Int) {
        context.playerPrefsDataStore.edit { it[Keys.SKIP_FWD] = seconds }
    }

    suspend fun setSkipBackSeconds(seconds: Int) {
        context.playerPrefsDataStore.edit { it[Keys.SKIP_BACK] = seconds }
    }

    suspend fun setSmartRewindSeconds(seconds: Int) {
        context.playerPrefsDataStore.edit { it[Keys.SMART_REWIND] = seconds }
    }
}
