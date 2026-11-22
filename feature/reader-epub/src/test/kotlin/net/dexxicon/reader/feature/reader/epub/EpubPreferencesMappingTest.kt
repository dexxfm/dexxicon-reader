package net.dexxicon.reader.feature.reader.epub

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderFontFamily
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.datastore.ReaderScrollMode
import net.dexxicon.reader.core.datastore.ReaderTheme
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubPreferencesMappingTest {

    @Test fun `page layout maps to Readium column count`() {
        fun layout(l: ReaderPageLayout) =
            ReaderDisplayPreferences(pageLayout = l).toEpubPreferences(systemInDark = false).columnCount

        assertThat(layout(ReaderPageLayout.AUTO)).isEqualTo(ColumnCount.AUTO)
        assertThat(layout(ReaderPageLayout.SINGLE)).isEqualTo(ColumnCount.ONE)
        assertThat(layout(ReaderPageLayout.DOUBLE)).isEqualTo(ColumnCount.TWO)
    }

    @Test fun `fit mode adjusts page margins, widest for page width`() {
        fun margins(f: ReaderFitMode) =
            ReaderDisplayPreferences(fitMode = f).toEpubPreferences(systemInDark = false).pageMargins!!

        assertThat(margins(ReaderFitMode.PAGE_WIDTH)).isLessThan(margins(ReaderFitMode.PAGE_FIT))
        assertThat(margins(ReaderFitMode.ACTUAL_SIZE)).isGreaterThan(margins(ReaderFitMode.PAGE_FIT))
        assertThat(margins(ReaderFitMode.PAGE_HEIGHT)).isEqualTo(margins(ReaderFitMode.PAGE_FIT))
    }

    @Test fun `font family maps to Readium font family and null preserves publisher default`() {
        val mappings = mapOf(
            ReaderFontFamily.SERIF to "serif",
            ReaderFontFamily.SANS_SERIF to "sans-serif",
            ReaderFontFamily.CURSIVE to "cursive",
            ReaderFontFamily.FANTASY to "fantasy",
            ReaderFontFamily.MONOSPACE to "monospace",
            ReaderFontFamily.ACCESSIBLE_DFA to "AccessibleDfA",
            ReaderFontFamily.IA_WRITER_DUOSPACE to "IA Writer Duospace",
            ReaderFontFamily.OPEN_DYSLEXIC to "OpenDyslexic",
        )

        mappings.forEach { (family, expectedName) ->
            val preference = ReaderDisplayPreferences(fontFamily = family)
                .toEpubPreferences(systemInDark = false)
            assertThat(preference.fontFamily?.name).isEqualTo(expectedName)
        }
        assertThat(
            ReaderDisplayPreferences().toEpubPreferences(systemInDark = false).fontFamily,
        ).isNull()
    }

    @Test fun `grey background paints explicit colours over a light base`() {
        val grey = ReaderDisplayPreferences(theme = ReaderTheme.GREY).toEpubPreferences(systemInDark = true)
        assertThat(grey.theme).isEqualTo(Theme.LIGHT)
        assertThat(grey.backgroundColor).isNotNull()
        assertThat(grey.textColor).isNotNull()

        val dark = ReaderDisplayPreferences(theme = ReaderTheme.DARK).toEpubPreferences(systemInDark = false)
        assertThat(dark.theme).isEqualTo(Theme.DARK)
        assertThat(dark.backgroundColor).isNull()
    }

    @Test fun `custom themes apply their exact background and foreground colours`() {
        val palettes = mapOf(
            ReaderTheme.SEPIA_DARK to (0xFFC4B090.toInt() to 0xFF2E2014.toInt()),
            ReaderTheme.MIDNIGHT to (0xFF0F172A.toInt() to 0xFFEDF4FC.toInt()),
            ReaderTheme.NORD to (0xFF2E3440.toInt() to 0xFFE3E9F5.toInt()),
        )

        palettes.forEach { (theme, colors) ->
            val preferences = ReaderDisplayPreferences(theme = theme)
                .toEpubPreferences(systemInDark = false)
            assertThat(preferences.theme).isEqualTo(Theme.LIGHT)
            assertThat(preferences.backgroundColor?.int).isEqualTo(colors.first)
            assertThat(preferences.textColor?.int).isEqualTo(colors.second)
        }
    }

    @Test fun `auto page layout switches to two columns on a wide viewport`() {
        val narrow = ReaderDisplayPreferences(pageLayout = ReaderPageLayout.AUTO)
            .toEpubPreferences(systemInDark = false, wideViewport = false)
        assertThat(narrow.columnCount).isEqualTo(ColumnCount.AUTO)

        val wide = ReaderDisplayPreferences(pageLayout = ReaderPageLayout.AUTO)
            .toEpubPreferences(systemInDark = false, wideViewport = true)
        assertThat(wide.columnCount).isEqualTo(ColumnCount.TWO)
    }

    @Test fun `a manual page layout choice ignores viewport width`() {
        fun columns(layout: ReaderPageLayout, wide: Boolean) =
            ReaderDisplayPreferences(pageLayout = layout)
                .toEpubPreferences(systemInDark = false, wideViewport = wide).columnCount

        assertThat(columns(ReaderPageLayout.SINGLE, wide = true)).isEqualTo(ColumnCount.ONE)
        assertThat(columns(ReaderPageLayout.DOUBLE, wide = false)).isEqualTo(ColumnCount.TWO)
    }

    @Test fun `scroll mode drives the Readium scroll flag`() {
        fun scroll(m: ReaderScrollMode) =
            ReaderDisplayPreferences(scrollMode = m).toEpubPreferences(systemInDark = false).scroll

        assertThat(scroll(ReaderScrollMode.PAGED)).isFalse()
        assertThat(scroll(ReaderScrollMode.SCROLL)).isTrue()
        assertThat(scroll(ReaderScrollMode.CONTINUOUS)).isTrue()
    }
}
