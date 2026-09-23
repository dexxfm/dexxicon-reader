package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeriesNumberTest {

    @Test
    fun `whole numbers drop the decimal`() {
        assertThat(seriesNumberLabel(3.0)).isEqualTo("3")
        assertThat(seriesNumberLabel(0.0)).isEqualTo("0")
        assertThat(seriesNumberLabel(12.0)).isEqualTo("12")
    }

    @Test
    fun `fractional positions keep their decimals`() {
        assertThat(seriesNumberLabel(3.5)).isEqualTo("3.5")
        assertThat(seriesNumberLabel(1.25)).isEqualTo("1.25")
    }

    @Test
    fun `float noise is rounded away`() {
        assertThat(seriesNumberLabel(2.0000001)).isEqualTo("2")
    }

    @Test
    fun `missing or nonsensical indexes have no label`() {
        assertThat(seriesNumberLabel(null)).isNull()
        assertThat(seriesNumberLabel(-1.0)).isNull()
        assertThat(seriesNumberLabel(Double.NaN)).isNull()
    }

    @Test
    fun `position text names the series when it has one`() {
        assertThat(seriesPositionText("Mistborn", 3.0)).isEqualTo("Mistborn #3")
        assertThat(seriesPositionText("  ", 3.0)).isEqualTo("#3")
        assertThat(seriesPositionText(null, 2.5)).isEqualTo("#2.5")
        assertThat(seriesPositionText("Mistborn", null)).isNull()
    }
}
