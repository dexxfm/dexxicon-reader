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
import org.readium.r2.navigator.preferences.TextAlign
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

    @Test fun `font scale maps directly to Readium proportional font size`() {
        mapOf(
            0.5 to 0.5,
            0.6 to 0.6,
            1.0 to 1.0,
            1.7 to 1.7,
            3.0 to 3.0,
        ).forEach { (scale, expectedScale) ->
            val fontSize = ReaderDisplayPreferences(fontScale = scale)
                .toEpubPreferences(systemInDark = false).fontSize
            assertThat(fontSize).isEqualTo(expectedScale)
        }
    }

    @Test fun `selected line spacing disables publisher styles and maps to Readium line height`() {
        mapOf(1.0 to 1.0, 1.5 to 1.5, 2.4 to 2.4, 3.0 to 3.0).forEach { (spacing, expected) ->
            val preferences = ReaderDisplayPreferences(lineSpacing = spacing)
                .toEpubPreferences(systemInDark = false)
            assertThat(preferences.lineHeight).isEqualTo(expected)
            assertThat(preferences.publisherStyles).isFalse()
        }

        val publisherDefault = ReaderDisplayPreferences().toEpubPreferences(systemInDark = false)
        assertThat(publisherDefault.lineHeight).isNull()
        assertThat(publisherDefault.publisherStyles).isNull()
    }

    @Test fun `paragraph spacing maps to Readium rem value and disables publisher styles`() {
        mapOf(0.0 to 0.0, 0.1 to 0.1, 1.0 to 1.0, 1.7 to 1.7, 2.0 to 2.0)
            .forEach { (spacing, expected) ->
                val preferences = ReaderDisplayPreferences(paragraphSpacing = spacing)
                    .toEpubPreferences(systemInDark = false)
                assertThat(preferences.paragraphSpacing).isEqualTo(expected)
                assertThat(preferences.publisherStyles).isFalse()
            }

        val publisherDefault = ReaderDisplayPreferences().toEpubPreferences(systemInDark = false)
        assertThat(publisherDefault.paragraphSpacing).isNull()
        assertThat(publisherDefault.publisherStyles).isNull()
    }

    @Test fun `typography toggles map to Readium overrides and preserve defaults when unset`() {
        val defaults = ReaderDisplayPreferences().toEpubPreferences(systemInDark = false)
        assertThat(defaults.paragraphIndent).isNull()
        assertThat(defaults.textAlign).isNull()
        assertThat(defaults.hyphens).isNull()
        assertThat(defaults.publisherStyles).isNull()

        val enabled = ReaderDisplayPreferences(
            paragraphIndent = true,
            justification = true,
            hyphenation = true,
        ).toEpubPreferences(systemInDark = false)
        assertThat(enabled.paragraphIndent).isEqualTo(1.0)
        assertThat(enabled.textAlign).isEqualTo(TextAlign.JUSTIFY)
        assertThat(enabled.hyphens).isTrue()
        assertThat(enabled.publisherStyles).isFalse()

        val disabled = ReaderDisplayPreferences(
            paragraphIndent = false,
            justification = false,
            hyphenation = false,
        ).toEpubPreferences(systemInDark = false)
        assertThat(disabled.paragraphIndent).isEqualTo(0.0)
        assertThat(disabled.textAlign).isEqualTo(TextAlign.START)
        assertThat(disabled.hyphens).isFalse()
        assertThat(disabled.publisherStyles).isFalse()
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
            ReaderTheme.CUSTOM_1 to (
                ReaderDisplayPreferences.DEFAULT_CUSTOM_1_BACKGROUND to
                    ReaderDisplayPreferences.DEFAULT_CUSTOM_1_FOREGROUND
                ),
            ReaderTheme.CUSTOM_2 to (
                ReaderDisplayPreferences.DEFAULT_CUSTOM_2_BACKGROUND to
                    ReaderDisplayPreferences.DEFAULT_CUSTOM_2_FOREGROUND
                ),
        )

        palettes.forEach { (theme, colors) ->
            val preferences = ReaderDisplayPreferences(theme = theme)
                .toEpubPreferences(systemInDark = false)
            assertThat(preferences.theme).isEqualTo(Theme.LIGHT)
            assertThat(preferences.backgroundColor?.int).isEqualTo(colors.first)
            assertThat(preferences.textColor?.int).isEqualTo(colors.second)
        }

        val edited = ReaderDisplayPreferences(
            theme = ReaderTheme.CUSTOM_1,
            custom1Background = 0xFF123456.toInt(),
            custom1Foreground = 0xFFABCDEF.toInt(),
        ).toEpubPreferences(systemInDark = false)
        assertThat(edited.backgroundColor?.int).isEqualTo(0xFF123456.toInt())
        assertThat(edited.textColor?.int).isEqualTo(0xFFABCDEF.toInt())
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
