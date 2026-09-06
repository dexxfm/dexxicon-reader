package net.dexxicon.reader.feature.reader.epub

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
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import javax.inject.Inject
import javax.inject.Singleton

/** User-facing EPUB reader look & feel. Applies to every book (per-book overrides can come later). */
data class ReaderDisplayPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val scroll: Boolean = false,
) {
    fun toEpubPreferences(systemInDark: Boolean): EpubPreferences {
        val resolvedTheme = when (theme) {
            ReaderTheme.LIGHT -> Theme.LIGHT
            ReaderTheme.SEPIA -> Theme.SEPIA
            ReaderTheme.DARK -> Theme.DARK
            ReaderTheme.SYSTEM -> if (systemInDark) Theme.DARK else Theme.LIGHT
        }
        return EpubPreferences(
            fontSize = fontScale,
            theme = resolvedTheme,
            scroll = scroll,
        )
    }
}

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }

private val Context.readerPrefsDataStore: DataStore<Preferences> by preferencesDataStore("reader_epub_prefs")

@Singleton
class ReaderPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FONT_SCALE = doublePreferencesKey("font_scale")
        val THEME = stringPreferencesKey("theme")
        val SCROLL = booleanPreferencesKey("scroll")
    }

    val preferences: Flow<ReaderDisplayPreferences> =
        context.readerPrefsDataStore.data.map { prefs ->
            ReaderDisplayPreferences(
                fontScale = prefs[Keys.FONT_SCALE] ?: 1.0,
                theme = prefs[Keys.THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                    ?: ReaderTheme.SYSTEM,
                scroll = prefs[Keys.SCROLL] ?: false,
            )
        }

    suspend fun update(transform: (ReaderDisplayPreferences) -> ReaderDisplayPreferences) {
        context.readerPrefsDataStore.edit { prefs ->
            val current = ReaderDisplayPreferences(
                fontScale = prefs[Keys.FONT_SCALE] ?: 1.0,
                theme = prefs[Keys.THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                    ?: ReaderTheme.SYSTEM,
                scroll = prefs[Keys.SCROLL] ?: false,
            )
            val next = transform(current)
            prefs[Keys.FONT_SCALE] = next.fontScale
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.SCROLL] = next.scroll
        }
    }
}
