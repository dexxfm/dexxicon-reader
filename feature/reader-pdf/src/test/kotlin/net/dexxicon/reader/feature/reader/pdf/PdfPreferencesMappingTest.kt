package net.dexxicon.reader.feature.reader.pdf

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import org.junit.Test
import org.readium.r2.navigator.preferences.Fit

class PdfPreferencesMappingTest {
    @Test
    fun `PDF fit uses its own preference regardless of other readers`() {
        val preferences = ReaderDisplayPreferences(
            comicFitMode = ReaderFitMode.PAGE_WIDTH,
            pdfFitMode = ReaderFitMode.PAGE_FIT,
        )
        assertThat(preferences.toPdfiumPreferences().fit).isEqualTo(Fit.CONTAIN)
        assertThat(preferences.copy(pdfFitMode = ReaderFitMode.PAGE_WIDTH).toPdfiumPreferences().fit)
            .isEqualTo(Fit.WIDTH)
    }
}
