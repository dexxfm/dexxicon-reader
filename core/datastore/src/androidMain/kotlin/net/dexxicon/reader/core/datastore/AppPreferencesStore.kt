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
import net.dexxicon.reader.core.model.BookViewMode
import javax.inject.Inject
import javax.inject.Singleton

enum class AppTheme { SYSTEM, LIGHT, DARK }

/** What tapping a book cover does in the per-server catalog and the merged Browse screen.
 * Home's Continue/On Deck shelves always jump straight to the reader regardless of this —
 * they're a separate, deliberate shortcut, not a generic "cover tap." */
enum class CoverTapAction { OPEN_DETAILS, OPEN_BOOK }

/** 10 GB — the out-of-the-box cap on downloaded media. */
const val DEFAULT_DOWNLOAD_LIMIT_BYTES: Long = 10L * 1024 * 1024 * 1024

data class AppPreferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val downloadsWifiOnly: Boolean = false,
    /** Cap on total downloaded-media size; null = no limit. */
    val downloadLimitBytes: Long? = DEFAULT_DOWNLOAD_LIMIT_BYTES,
    /** The layout new lists start with, chosen in Settings. */
    val bookViewDefault: BookViewMode = BookViewMode.GRID,
    /** Browse's current layout — its own toggle, falls back to [bookViewDefault]. */
    val browseView: BookViewMode = BookViewMode.GRID,
    /** A server catalog's current layout — its own toggle, falls back to [bookViewDefault]. */
    val catalogView: BookViewMode = BookViewMode.GRID,
    /** What tapping a cover does in the catalog / Browse screens. */
    val coverTapAction: CoverTapAction = CoverTapAction.OPEN_DETAILS,
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
        val BOOK_VIEW_DEFAULT = stringPreferencesKey("book_view")
        val BROWSE_VIEW = stringPreferencesKey("book_view_browse")
        val CATALOG_VIEW = stringPreferencesKey("book_view_catalog")
        val COVER_TAP_ACTION = stringPreferencesKey("cover_tap_action")
    }

    val preferences: Flow<AppPreferences> = context.appPrefsDataStore.data.map { p ->
        val default = p.mode(Keys.BOOK_VIEW_DEFAULT) ?: BookViewMode.GRID
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
            bookViewDefault = default,
            browseView = p.mode(Keys.BROWSE_VIEW) ?: default,
            catalogView = p.mode(Keys.CATALOG_VIEW) ?: default,
            coverTapAction = p[Keys.COVER_TAP_ACTION]
                ?.let { runCatching { CoverTapAction.valueOf(it) }.getOrNull() }
                ?: CoverTapAction.OPEN_DETAILS,
        )
    }

    private fun Preferences.mode(key: Preferences.Key<String>): BookViewMode? =
        this[key]?.let { runCatching { BookViewMode.valueOf(it) }.getOrNull() }

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

    /** Change the Settings default and snap every screen's current layout back to it. */
    suspend fun setBookViewDefault(mode: BookViewMode) {
        context.appPrefsDataStore.edit {
            it[Keys.BOOK_VIEW_DEFAULT] = mode.name
            it.remove(Keys.BROWSE_VIEW)
            it.remove(Keys.CATALOG_VIEW)
        }
    }

    /** Browse's own layout toggle — leaves the Settings default alone. */
    suspend fun setBrowseView(mode: BookViewMode) {
        context.appPrefsDataStore.edit { it[Keys.BROWSE_VIEW] = mode.name }
    }

    /** A server catalog's own layout toggle — leaves the Settings default alone. */
    suspend fun setCatalogView(mode: BookViewMode) {
        context.appPrefsDataStore.edit { it[Keys.CATALOG_VIEW] = mode.name }
    }

    suspend fun setCoverTapAction(action: CoverTapAction) {
        context.appPrefsDataStore.edit { it[Keys.COVER_TAP_ACTION] = action.name }
    }
}
