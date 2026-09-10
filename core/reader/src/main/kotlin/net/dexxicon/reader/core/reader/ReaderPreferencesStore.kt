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
    /**
     * How a page is sized within the viewport. Honoured directly by fixed-layout books,
     * PDFs and comics; for reflowable EPUB text it maps onto the page margins.
     */
    val fitMode: ReaderFitMode = ReaderFitMode.PAGE_FIT,
    /**
     * One page at a time, or a two-page spread once the screen is wide enough — tablets
     * and unfolded foldables. [ReaderPageLayout.AUTO] lets the navigator decide.
     */
    val pageLayout: ReaderPageLayout = ReaderPageLayout.AUTO,
    /**
     * Pagination vs. scrolling. Only [ReaderScrollMode.PAGED] and [ReaderScrollMode.SCROLL]
     * reach a navigator today; the remaining entries are scaffolding for a later pass.
     */
    val scrollMode: ReaderScrollMode = ReaderScrollMode.PAGED,
    /** Tap near a page edge to turn the page. */
    val tapNavigation: Boolean = true,
    /** How far a drag has to travel before it commits to a page turn. */
    val swipeSensitivity: ReaderSwipeSensitivity = ReaderSwipeSensitivity.MEDIUM,
) {
    /** Legacy shorthand: the EPUB/PDF navigators still take a plain scroll flag. */
    val scroll: Boolean get() = scrollMode.scrolling
}

/**
 * How eager the drag-to-turn gesture is in the PDF and comic readers. [commitFraction] is
 * the share of the page width a drag must cover (or a quick flick must exceed) to turn the
 * page; below it, the page slides back. EPUB keeps Readium's own fixed drag behaviour.
 */
enum class ReaderSwipeSensitivity(val label: String, val commitFraction: Float) {
    LOW("Low", 0.50f),
    MEDIUM("Medium", 0.33f),
    HIGH("High", 0.20f),
}

/** Reader page colours. Doubles as the EPUB reading theme. */
enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, GREY, DARK }

/**
 * Page-fit modes, borrowed from image/PDF viewers. Reflowable EPUB has no true "fit", so
 * the EPUB mapping approximates these with margin width; PDFs and fixed-layout books use
 * them literally once wired.
 */
enum class ReaderFitMode { PAGE_FIT, PAGE_WIDTH, PAGE_HEIGHT, ACTUAL_SIZE }

/** Single page, forced two-page spread, or automatic based on the screen width. */
enum class ReaderPageLayout { AUTO, SINGLE, DOUBLE }

/**
 * Reading flow. [PAGED] and [SCROLL] are live; [CONTINUOUS] is scaffolded — persisted and
 * selectable in code, but it currently behaves like [SCROLL] until a navigator supports a
 * distinct continuous/webtoon layout.
 */
enum class ReaderScrollMode(val scrolling: Boolean) {
    PAGED(false),
    SCROLL(true),
    CONTINUOUS(true),
}

private val Context.readerPrefsDataStore: DataStore<Preferences> by preferencesDataStore("reader_prefs")

@Singleton
class ReaderPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FONT_SCALE = doublePreferencesKey("font_scale")
        val THEME = stringPreferencesKey("theme")
        val FIT_MODE = stringPreferencesKey("fit_mode")
        val PAGE_LAYOUT = stringPreferencesKey("page_layout")
        val SCROLL_MODE = stringPreferencesKey("scroll_mode")
        val SCROLL = booleanPreferencesKey("scroll")
        val TAP_NAV = booleanPreferencesKey("tap_navigation")
        val SWIPE_SENSITIVITY = stringPreferencesKey("swipe_sensitivity")
    }

    val preferences: Flow<ReaderDisplayPreferences> =
        context.readerPrefsDataStore.data.map { it.toPrefs() }

    suspend fun update(transform: (ReaderDisplayPreferences) -> ReaderDisplayPreferences) {
        context.readerPrefsDataStore.edit { prefs ->
            val next = transform(prefs.toPrefs())
            prefs[Keys.FONT_SCALE] = next.fontScale
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.FIT_MODE] = next.fitMode.name
            prefs[Keys.PAGE_LAYOUT] = next.pageLayout.name
            prefs[Keys.SCROLL_MODE] = next.scrollMode.name
            prefs[Keys.SCROLL] = next.scroll
            prefs[Keys.TAP_NAV] = next.tapNavigation
            prefs[Keys.SWIPE_SENSITIVITY] = next.swipeSensitivity.name
        }
    }

    private fun Preferences.toPrefs() = ReaderDisplayPreferences(
        fontScale = this[Keys.FONT_SCALE] ?: 1.0,
        theme = this[Keys.THEME]?.let { enumOrNull<ReaderTheme>(it) } ?: ReaderTheme.SYSTEM,
        fitMode = this[Keys.FIT_MODE]?.let { enumOrNull<ReaderFitMode>(it) } ?: ReaderFitMode.PAGE_FIT,
        pageLayout = this[Keys.PAGE_LAYOUT]?.let { enumOrNull<ReaderPageLayout>(it) }
            ?: ReaderPageLayout.AUTO,
        scrollMode = this[Keys.SCROLL_MODE]?.let { enumOrNull<ReaderScrollMode>(it) }
            // Fall back to the pre-scroll-mode boolean so existing readers keep their choice.
            ?: (this[Keys.SCROLL] ?: false).let { if (it) ReaderScrollMode.SCROLL else ReaderScrollMode.PAGED },
        tapNavigation = this[Keys.TAP_NAV] ?: true,
        swipeSensitivity = this[Keys.SWIPE_SENSITIVITY]?.let { enumOrNull<ReaderSwipeSensitivity>(it) }
            ?: ReaderSwipeSensitivity.MEDIUM,
    )
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    runCatching { enumValueOf<T>(name) }.getOrNull()
