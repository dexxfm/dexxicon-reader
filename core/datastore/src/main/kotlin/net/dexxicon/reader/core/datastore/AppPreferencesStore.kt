package net.dexxicon.reader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppTheme { SYSTEM, LIGHT, DARK }

/** 10 GB — the out-of-the-box cap on downloaded media. */
const val DEFAULT_DOWNLOAD_LIMIT_BYTES: Long = 10L * 1024 * 1024 * 1024

data class AppPreferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val downloadsWifiOnly: Boolean = false,
    /** Cap on total downloaded-media size; null = no limit. */
    val downloadLimitBytes: Long? = DEFAULT_DOWNLOAD_LIMIT_BYTES,
)

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore("app_prefs")

@Singleton
class AppPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        val DOWNLOAD_LIMIT = longPreferencesKey("download_limit_bytes")
    }

    val preferences: Flow<AppPreferences> = context.appPrefsDataStore.data.map { p ->
        AppPreferences(
            theme = p[Keys.THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: AppTheme.SYSTEM,
            downloadsWifiOnly = p[Keys.WIFI_ONLY] ?: false,
            // Absent = default cap; a stored value <= 0 = "no limit".
            downloadLimitBytes = if (p.contains(Keys.DOWNLOAD_LIMIT)) {
                p[Keys.DOWNLOAD_LIMIT]?.takeIf { it > 0L }
            } else {
                DEFAULT_DOWNLOAD_LIMIT_BYTES
            },
        )
    }

    suspend fun setTheme(theme: AppTheme) {
        context.appPrefsDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setDownloadsWifiOnly(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    /** [bytes] null or <= 0 removes the cap. */
    suspend fun setDownloadLimit(bytes: Long?) {
        context.appPrefsDataStore.edit { it[Keys.DOWNLOAD_LIMIT] = bytes?.coerceAtLeast(0L) ?: 0L }
    }
}
