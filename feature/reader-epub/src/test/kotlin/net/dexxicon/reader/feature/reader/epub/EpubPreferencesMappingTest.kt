package net.dexxicon.reader.feature.reader.epub

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
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

    @Test fun `grey background paints explicit colours over a light base`() {
        val grey = ReaderDisplayPreferences(theme = ReaderTheme.GREY).toEpubPreferences(systemInDark = true)
        assertThat(grey.theme).isEqualTo(Theme.LIGHT)
        assertThat(grey.backgroundColor).isNotNull()
        assertThat(grey.textColor).isNotNull()

        val dark = ReaderDisplayPreferences(theme = ReaderTheme.DARK).toEpubPreferences(systemInDark = false)
        assertThat(dark.theme).isEqualTo(Theme.DARK)
        assertThat(dark.backgroundColor).isNull()
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
