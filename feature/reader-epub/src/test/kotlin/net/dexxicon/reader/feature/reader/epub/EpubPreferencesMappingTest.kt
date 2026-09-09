package net.dexxicon.reader.feature.reader.epub

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderFitMode
import net.dexxicon.reader.core.reader.ReaderPageLayout
import net.dexxicon.reader.core.reader.ReaderScrollMode
import net.dexxicon.reader.core.reader.ReaderTheme
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

    @Test fun `scroll mode drives the Readium scroll flag`() {
        fun scroll(m: ReaderScrollMode) =
            ReaderDisplayPreferences(scrollMode = m).toEpubPreferences(systemInDark = false).scroll

        assertThat(scroll(ReaderScrollMode.PAGED)).isFalse()
        assertThat(scroll(ReaderScrollMode.SCROLL)).isTrue()
        assertThat(scroll(ReaderScrollMode.CONTINUOUS)).isTrue()
    }
}
