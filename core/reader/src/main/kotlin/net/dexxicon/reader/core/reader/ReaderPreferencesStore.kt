package net.dexxicon.reader.core.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Reader look & feel, shared by every in-app reader. */
data class ReaderDisplayPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val scroll: Boolean = false,
    /** Tap near a page edge to turn the page. */
    val tapNavigation: Boolean = true,
)

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }

private val Context.readerPrefsDataStore: DataStore<Preferences> by preferencesDataStore("reader_prefs")

@Singleton
class ReaderPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FONT_SCALE = doublePreferencesKey("font_scale")
        val THEME = stringPreferencesKey("theme")
        val SCROLL = booleanPreferencesKey("scroll")
        val TAP_NAV = booleanPreferencesKey("tap_navigation")
    }

    val preferences: Flow<ReaderDisplayPreferences> =
        context.readerPrefsDataStore.data.map { it.toPrefs() }

    suspend fun update(transform: (ReaderDisplayPreferences) -> ReaderDisplayPreferences) {
        context.readerPrefsDataStore.edit { prefs ->
            val next = transform(prefs.toPrefs())
            prefs[Keys.FONT_SCALE] = next.fontScale
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.SCROLL] = next.scroll
            prefs[Keys.TAP_NAV] = next.tapNavigation
        }
    }

    private fun Preferences.toPrefs() = ReaderDisplayPreferences(
        fontScale = this[Keys.FONT_SCALE] ?: 1.0,
        theme = this[Keys.THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
            ?: ReaderTheme.SYSTEM,
        scroll = this[Keys.SCROLL] ?: false,
        tapNavigation = this[Keys.TAP_NAV] ?: true,
    )
}
