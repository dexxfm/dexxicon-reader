package net.dexxicon.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.dexxicon.reader.core.model.BookViewMode
import okio.Path.Companion.toPath

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

/**
 * Phase 4 Stage D (issue #133) — moved to commonMain so `shared/library/LibraryState` can
 * read `browseView`/`coverTapAction` directly (and `AppContainer` can build it for
 * `AndroidDownloadRepository`, which already took one). `@Inject`/`@Singleton` dropped, same
 * as every other Stage B1/C conversion; the previous Android-only `Context.preferencesDataStore`
 * delegate is replaced with [appPreferencesFilePath] (expect, one `actual` per platform) plus
 * the same [dataStoreFor] memoization [SyncStateStore] uses.
 *
 * That memoization matters more here than it first looks: the old `Context.preferencesDataStore`
 * delegate had its *own* built-in per-`Context` instance cache, so constructing this class
 * twice (once from `:app`'s Hilt graph, once from `:shared`'s `AppContainer`) was accidentally
 * safe. `PreferenceDataStoreFactory.createWithPath` has no such cache — without memoizing by
 * resolved path here, this class would hit the exact crash Stage C's `SyncStateStore` did
 * (`IllegalStateException`: two `DataStore<Preferences>` instances open on the same file).
 *
 * [appPreferencesFilePath]'s Android `actual` deliberately reproduces
 * `Context.preferencesDataStore("app_prefs")`'s original file location
 * (`filesDir/datastore/app_prefs.preferences_pb`) rather than [SyncStateStore]'s flatter
 * `filesDir/sync_state.preferences_pb` convention — this file holds real, already-populated
 * user preferences (theme, download limits, view mode), and getting the path wrong would
 * silently reset them all to defaults on upgrade.
 */
class AppPreferencesStore(context: PlatformStorageContext) {
    private val dataStore: DataStore<Preferences> = dataStoreFor(appPreferencesFilePath(context))

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
        val DOWNLOAD_LIMIT = longPreferencesKey("download_limit_bytes")
        val BOOK_VIEW_DEFAULT = stringPreferencesKey("book_view")
        val BROWSE_VIEW = stringPreferencesKey("book_view_browse")
        val CATALOG_VIEW = stringPreferencesKey("book_view_catalog")
        val COVER_TAP_ACTION = stringPreferencesKey("cover_tap_action")
    }

    val preferences: Flow<AppPreferences> = dataStore.data.map { p ->
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
        dataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setDownloadsWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    /** [bytes] null or <= 0 removes the cap. */
    suspend fun setDownloadLimit(bytes: Long?) {
        dataStore.edit { it[Keys.DOWNLOAD_LIMIT] = bytes?.coerceAtLeast(0L) ?: 0L }
    }

    /** Change the Settings default and snap every screen's current layout back to it. */
    suspend fun setBookViewDefault(mode: BookViewMode) {
        dataStore.edit {
            it[Keys.BOOK_VIEW_DEFAULT] = mode.name
            it.remove(Keys.BROWSE_VIEW)
            it.remove(Keys.CATALOG_VIEW)
        }
    }

    /** Browse's own layout toggle — leaves the Settings default alone. */
    suspend fun setBrowseView(mode: BookViewMode) {
        dataStore.edit { it[Keys.BROWSE_VIEW] = mode.name }
    }

    /** A server catalog's own layout toggle — leaves the Settings default alone. */
    suspend fun setCatalogView(mode: BookViewMode) {
        dataStore.edit { it[Keys.CATALOG_VIEW] = mode.name }
    }

    suspend fun setCoverTapAction(action: CoverTapAction) {
        dataStore.edit { it[Keys.COVER_TAP_ACTION] = action.name }
    }

    private companion object {
        /** Same reasoning as [SyncStateStore]'s own `dataStores` cache — duplicated rather
         * than shared, since two call sites don't justify a shared abstraction. */
        private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

        private fun dataStoreFor(path: String): DataStore<Preferences> =
            dataStores.getOrPut(path) {
                PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
            }
    }
}

/** Opaque per-platform handle [appPreferencesFilePath] needs to locate the preferences file —
 * see [PlatformStorageContext] (shared with [SyncStateStore]). */
expect fun appPreferencesFilePath(context: PlatformStorageContext): String
