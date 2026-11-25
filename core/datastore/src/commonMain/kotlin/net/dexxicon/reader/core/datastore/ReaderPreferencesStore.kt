package net.dexxicon.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import okio.Path.Companion.toPath

/** Reader look & feel, shared by every in-app reader. */
data class ReaderDisplayPreferences(
    val fontScale: Double = DEFAULT_FONT_SCALE,
    val lineSpacing: Double? = null,
    val paragraphSpacing: Double? = null,
    val paragraphIndent: Boolean? = null,
    val justification: Boolean? = null,
    val hyphenation: Boolean? = null,
    val custom1Background: Int = DEFAULT_CUSTOM_1_BACKGROUND,
    val custom1Foreground: Int = DEFAULT_CUSTOM_1_FOREGROUND,
    val custom2Background: Int = DEFAULT_CUSTOM_2_BACKGROUND,
    val custom2Foreground: Int = DEFAULT_CUSTOM_2_FOREGROUND,
    /** EPUB typeface override. Null leaves the publication's own font in effect. */
    val fontFamily: ReaderFontFamily? = null,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    /** Independent page-fit choices for PDF and comics. */
    val pdfFitMode: ReaderFitMode = ReaderFitMode.PAGE_FIT,
    val comicFitMode: ReaderFitMode = ReaderFitMode.PAGE_FIT,
    /** EPUB margins as percentages of the available reading area. */
    val epubMarginTop: Int = DEFAULT_EPUB_VERTICAL_MARGIN,
    val epubMarginBottom: Int = DEFAULT_EPUB_VERTICAL_MARGIN,
    val epubMarginLeft: Int = DEFAULT_EPUB_HORIZONTAL_MARGIN,
    val epubMarginRight: Int = DEFAULT_EPUB_HORIZONTAL_MARGIN,
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
    /**
     * Comics only: auto-zoom into each detected panel in reading order before turning the
     * page, like a "guided view". Falls back silently to the plain full page on any page
     * where panels can't be confidently detected.
     */
    val comicSmartZoom: Boolean = false,
    /** Comics only: manga-style right-to-left panel order and page turns. */
    val comicRightToLeft: Boolean = false,
) {
    /** Legacy shorthand: the EPUB/PDF navigators still take a plain scroll flag. */
    val scroll: Boolean get() = scrollMode.scrolling

    /**
     * issue #261 — the page and text colours of a theme the readers paint on themselves (Sepia
     * Dark, Midnight, Nord and the two custom ones), so Android, iOS and the PDF/comic surfaces
     * all use the same palette. Null for the themes each reader draws its own way.
     */
    val paintedColors: ReaderPageColors?
        get() = when (theme) {
            ReaderTheme.SEPIA_DARK -> ReaderPageColors(SEPIA_DARK_BACKGROUND, SEPIA_DARK_TEXT)
            ReaderTheme.MIDNIGHT -> ReaderPageColors(MIDNIGHT_BACKGROUND, MIDNIGHT_TEXT)
            ReaderTheme.NORD -> ReaderPageColors(NORD_BACKGROUND, NORD_TEXT)
            ReaderTheme.CUSTOM_1 -> ReaderPageColors(custom1Background, custom1Foreground)
            ReaderTheme.CUSTOM_2 -> ReaderPageColors(custom2Background, custom2Foreground)
            ReaderTheme.SYSTEM, ReaderTheme.LIGHT, ReaderTheme.SEPIA, ReaderTheme.GREY, ReaderTheme.DARK -> null
        }

    companion object {
        const val MIN_FONT_SCALE = 0.5
        const val MAX_FONT_SCALE = 3.0
        const val SCALE_STEP = 0.1
        const val DEFAULT_FONT_SCALE = 1.0
        const val MIN_LINE_SPACING = 1.0
        const val MAX_LINE_SPACING = 3.0
        const val DEFAULT_LINE_SPACING = 1.0
        const val MIN_PARAGRAPH_SPACING = 0.0
        const val MAX_PARAGRAPH_SPACING = 2.0
        const val DEFAULT_PARAGRAPH_SPACING = 0.0
        const val DEFAULT_EPUB_VERTICAL_MARGIN = 0
        const val DEFAULT_EPUB_HORIZONTAL_MARGIN = 5
        const val MAX_EPUB_MARGIN = 25
        const val DEFAULT_CUSTOM_1_BACKGROUND = 0xFFFFFFFF.toInt()
        const val DEFAULT_CUSTOM_1_FOREGROUND = 0xFF000000.toInt()
        const val DEFAULT_CUSTOM_2_BACKGROUND = 0xFF000000.toInt()
        const val DEFAULT_CUSTOM_2_FOREGROUND = 0xFFFFFFFF.toInt()
        const val SEPIA_DARK_BACKGROUND = 0xFFC4B090.toInt()
        const val SEPIA_DARK_TEXT = 0xFF2E2014.toInt()
        const val MIDNIGHT_BACKGROUND = 0xFF0F172A.toInt()
        const val MIDNIGHT_TEXT = 0xFFEDF4FC.toInt()
        const val NORD_BACKGROUND = 0xFF2E3440.toInt()
        const val NORD_TEXT = 0xFFE3E9F5.toInt()
    }
}

/** issue #261 — a page colour and its text colour, as ARGB. */
data class ReaderPageColors(val background: Int, val text: Int) {
    /** A dark page (highlights need a stronger tint on it): by the background's luminance. */
    val isDark: Boolean
        get() {
            val r = (background shr 16) and 0xFF
            val g = (background shr 8) and 0xFF
            val b = background and 0xFF
            return 0.2126 * r + 0.7152 * g + 0.0722 * b < 128
        }
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

/** Font families supported by Readium's EPUB navigator. */
enum class ReaderFontFamily(val label: String) {
    SERIF("Serif"),
    SANS_SERIF("Sans serif"),
    CURSIVE("Cursive"),
    FANTASY("Fantasy"),
    MONOSPACE("Monospace"),
    ACCESSIBLE_DFA("AccessibleDfA"),
    IA_WRITER_DUOSPACE("IA Writer Duospace"),
    OPEN_DYSLEXIC("OpenDyslexic"),
}

/** Reader page colours. Doubles as the EPUB reading theme. */
enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, SEPIA_DARK, GREY, DARK, MIDNIGHT, NORD, CUSTOM_1, CUSTOM_2 }

/**
 * Page-fit modes. The PDF and comic engines currently distinguish only page fit and width.
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

/**
 * Phase 4 Stage H (issue #145) — moved to commonMain following [AppPreferencesStore]'s
 * precedent: `@Inject`/`@Singleton` dropped (an explicit `@Provides` in `:app`'s `DataModule`
 * now supplies it to Hilt), the old Android-only `Context.preferencesDataStore` delegate
 * replaced with [readerPreferencesFilePath] (expect, one `actual` per platform) plus the same
 * [dataStoreFor] memoization [SyncStateStore]/[AppPreferencesStore] use. This was previously
 * `:core:reader`'s own `ReaderPreferencesStore` — that module stays Android-only (it wraps
 * Readium, which has no iOS build here), but the *preferences* have no Android dependency of
 * their own, so `:shared`'s Book Defaults screens can now read/write them directly.
 */
class ReaderPreferencesStore(context: PlatformStorageContext) {
    private val dataStore: DataStore<Preferences> = dataStoreFor(readerPreferencesFilePath(context))

    private object Keys {
        val FONT_SCALE = doublePreferencesKey("font_scale")
        val LINE_SPACING_OVERRIDE = doublePreferencesKey("line_height_override")
        val PARAGRAPH_SPACING_OVERRIDE = doublePreferencesKey("paragraph_spacing_override")
        val PARAGRAPH_INDENT = booleanPreferencesKey("paragraph_indent")
        val JUSTIFICATION = booleanPreferencesKey("justification")
        val HYPHENATION = booleanPreferencesKey("hyphenation")
        val CUSTOM_1_BACKGROUND = intPreferencesKey("custom_1_background")
        val CUSTOM_1_FOREGROUND = intPreferencesKey("custom_1_foreground")
        val CUSTOM_2_BACKGROUND = intPreferencesKey("custom_2_background")
        val CUSTOM_2_FOREGROUND = intPreferencesKey("custom_2_foreground")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val THEME = stringPreferencesKey("theme")
        val FIT_MODE = stringPreferencesKey("fit_mode")
        val PDF_FIT_MODE = stringPreferencesKey("pdf_fit_mode")
        val COMIC_FIT_MODE = stringPreferencesKey("comic_fit_mode")
        val EPUB_MARGIN_TOP = intPreferencesKey("epub_margin_top")
        val EPUB_MARGIN_BOTTOM = intPreferencesKey("epub_margin_bottom")
        val EPUB_MARGIN_LEFT = intPreferencesKey("epub_margin_left")
        val EPUB_MARGIN_RIGHT = intPreferencesKey("epub_margin_right")
        val PAGE_LAYOUT = stringPreferencesKey("page_layout")
        val SCROLL_MODE = stringPreferencesKey("scroll_mode")
        val SCROLL = booleanPreferencesKey("scroll")
        val TAP_NAV = booleanPreferencesKey("tap_navigation")
        val SWIPE_SENSITIVITY = stringPreferencesKey("swipe_sensitivity")
        val COMIC_SMART_ZOOM = booleanPreferencesKey("comic_smart_zoom")
        val COMIC_RIGHT_TO_LEFT = booleanPreferencesKey("comic_right_to_left")
    }

    val preferences: Flow<ReaderDisplayPreferences> = dataStore.data.map { it.toPrefs() }

    suspend fun update(transform: (ReaderDisplayPreferences) -> ReaderDisplayPreferences) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toPrefs())
            prefs[Keys.FONT_SCALE] = normalizeFontScale(next.fontScale)
            next.lineSpacing?.let { prefs[Keys.LINE_SPACING_OVERRIDE] = normalizeLineSpacing(it) }
                ?: prefs.remove(Keys.LINE_SPACING_OVERRIDE)
            next.paragraphSpacing?.let { prefs[Keys.PARAGRAPH_SPACING_OVERRIDE] = normalizeParagraphSpacing(it) }
                ?: prefs.remove(Keys.PARAGRAPH_SPACING_OVERRIDE)
            next.paragraphIndent?.let { prefs[Keys.PARAGRAPH_INDENT] = it }
                ?: prefs.remove(Keys.PARAGRAPH_INDENT)
            next.justification?.let { prefs[Keys.JUSTIFICATION] = it }
                ?: prefs.remove(Keys.JUSTIFICATION)
            next.hyphenation?.let { prefs[Keys.HYPHENATION] = it }
                ?: prefs.remove(Keys.HYPHENATION)
            prefs[Keys.CUSTOM_1_BACKGROUND] = next.custom1Background
            prefs[Keys.CUSTOM_1_FOREGROUND] = next.custom1Foreground
            prefs[Keys.CUSTOM_2_BACKGROUND] = next.custom2Background
            prefs[Keys.CUSTOM_2_FOREGROUND] = next.custom2Foreground
            next.fontFamily?.let { prefs[Keys.FONT_FAMILY] = it.name }
                ?: prefs.remove(Keys.FONT_FAMILY)
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.PDF_FIT_MODE] = normalizeFitMode(next.pdfFitMode).name
            prefs[Keys.COMIC_FIT_MODE] = normalizeFitMode(next.comicFitMode).name
            prefs[Keys.EPUB_MARGIN_TOP] = normalizeEpubMargin(next.epubMarginTop)
            prefs[Keys.EPUB_MARGIN_BOTTOM] = normalizeEpubMargin(next.epubMarginBottom)
            prefs[Keys.EPUB_MARGIN_LEFT] = normalizeEpubMargin(next.epubMarginLeft)
            prefs[Keys.EPUB_MARGIN_RIGHT] = normalizeEpubMargin(next.epubMarginRight)
            prefs[Keys.PAGE_LAYOUT] = next.pageLayout.name
            prefs[Keys.SCROLL_MODE] = next.scrollMode.name
            prefs[Keys.SCROLL] = next.scroll
            prefs[Keys.TAP_NAV] = next.tapNavigation
            prefs[Keys.SWIPE_SENSITIVITY] = next.swipeSensitivity.name
            prefs[Keys.COMIC_SMART_ZOOM] = next.comicSmartZoom
            prefs[Keys.COMIC_RIGHT_TO_LEFT] = next.comicRightToLeft
        }
    }

    private fun Preferences.toPrefs() = ReaderDisplayPreferences(
        fontScale = (this[Keys.FONT_SCALE] ?: ReaderDisplayPreferences.DEFAULT_FONT_SCALE).let(::normalizeFontScale),
        lineSpacing = this[Keys.LINE_SPACING_OVERRIDE]?.let(::normalizeLineSpacing),
        paragraphSpacing = this[Keys.PARAGRAPH_SPACING_OVERRIDE]?.let(::normalizeParagraphSpacing),
        paragraphIndent = this[Keys.PARAGRAPH_INDENT],
        justification = this[Keys.JUSTIFICATION],
        hyphenation = this[Keys.HYPHENATION],
        custom1Background = this[Keys.CUSTOM_1_BACKGROUND] ?: ReaderDisplayPreferences.DEFAULT_CUSTOM_1_BACKGROUND,
        custom1Foreground = this[Keys.CUSTOM_1_FOREGROUND] ?: ReaderDisplayPreferences.DEFAULT_CUSTOM_1_FOREGROUND,
        custom2Background = this[Keys.CUSTOM_2_BACKGROUND] ?: ReaderDisplayPreferences.DEFAULT_CUSTOM_2_BACKGROUND,
        custom2Foreground = this[Keys.CUSTOM_2_FOREGROUND] ?: ReaderDisplayPreferences.DEFAULT_CUSTOM_2_FOREGROUND,
        fontFamily = this[Keys.FONT_FAMILY]?.let { enumOrNull<ReaderFontFamily>(it) },
        theme = this[Keys.THEME]?.let { enumOrNull<ReaderTheme>(it) } ?: ReaderTheme.SYSTEM,
        pdfFitMode = fitMode(Keys.PDF_FIT_MODE),
        comicFitMode = fitMode(Keys.COMIC_FIT_MODE),
        epubMarginTop = normalizeEpubMargin(this[Keys.EPUB_MARGIN_TOP] ?: ReaderDisplayPreferences.DEFAULT_EPUB_VERTICAL_MARGIN),
        epubMarginBottom = normalizeEpubMargin(this[Keys.EPUB_MARGIN_BOTTOM] ?: ReaderDisplayPreferences.DEFAULT_EPUB_VERTICAL_MARGIN),
        epubMarginLeft = normalizeEpubMargin(this[Keys.EPUB_MARGIN_LEFT] ?: ReaderDisplayPreferences.DEFAULT_EPUB_HORIZONTAL_MARGIN),
        epubMarginRight = normalizeEpubMargin(this[Keys.EPUB_MARGIN_RIGHT] ?: ReaderDisplayPreferences.DEFAULT_EPUB_HORIZONTAL_MARGIN),
        pageLayout = this[Keys.PAGE_LAYOUT]?.let { enumOrNull<ReaderPageLayout>(it) }
            ?: ReaderPageLayout.AUTO,
        scrollMode = this[Keys.SCROLL_MODE]?.let { enumOrNull<ReaderScrollMode>(it) }
            // Fall back to the pre-scroll-mode boolean so existing readers keep their choice.
            ?: (this[Keys.SCROLL] ?: false).let { if (it) ReaderScrollMode.SCROLL else ReaderScrollMode.PAGED },
        tapNavigation = this[Keys.TAP_NAV] ?: true,
        swipeSensitivity = this[Keys.SWIPE_SENSITIVITY]?.let { enumOrNull<ReaderSwipeSensitivity>(it) }
            ?: ReaderSwipeSensitivity.MEDIUM,
        comicSmartZoom = this[Keys.COMIC_SMART_ZOOM] ?: false,
        comicRightToLeft = this[Keys.COMIC_RIGHT_TO_LEFT] ?: false,
    )

    private fun Preferences.fitMode(key: Preferences.Key<String>): ReaderFitMode =
        normalizeFitMode(
            (this[key] ?: this[Keys.FIT_MODE])?.let { enumOrNull<ReaderFitMode>(it) }
                ?: ReaderFitMode.PAGE_FIT,
        )

    private companion object {
        /** Same reasoning as [SyncStateStore]'s own `dataStores` cache. */
        private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

        private fun dataStoreFor(path: String): DataStore<Preferences> =
            dataStores.getOrPut(path) {
                PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
            }
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    runCatching { enumValueOf<T>(name) }.getOrNull()

private fun normalizeFitMode(value: ReaderFitMode): ReaderFitMode =
    if (value == ReaderFitMode.PAGE_WIDTH) ReaderFitMode.PAGE_WIDTH else ReaderFitMode.PAGE_FIT

private fun normalizeEpubMargin(value: Int): Int =
    value.coerceIn(0, ReaderDisplayPreferences.MAX_EPUB_MARGIN)

private fun normalizeFontScale(fontScale: Double): Double =
    normalizeScale(
        fontScale,
        ReaderDisplayPreferences.MIN_FONT_SCALE,
        ReaderDisplayPreferences.MAX_FONT_SCALE,
    )

private fun normalizeLineSpacing(lineSpacing: Double): Double =
    normalizeScale(
        lineSpacing,
        ReaderDisplayPreferences.MIN_LINE_SPACING,
        ReaderDisplayPreferences.MAX_LINE_SPACING,
    )

private fun normalizeParagraphSpacing(paragraphSpacing: Double): Double =
    normalizeScale(
        paragraphSpacing,
        ReaderDisplayPreferences.MIN_PARAGRAPH_SPACING,
        ReaderDisplayPreferences.MAX_PARAGRAPH_SPACING,
    )

private fun normalizeScale(value: Double, min: Double, max: Double): Double =
    (value.coerceIn(min, max) * 10).roundToInt() / 10.0

/** Opaque per-platform handle [readerPreferencesFilePath] needs to locate the preferences
 * file — see [PlatformStorageContext] (shared with [SyncStateStore]/[AppPreferencesStore]). */
expect fun readerPreferencesFilePath(context: PlatformStorageContext): String
