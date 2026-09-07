package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighlightColorTest {

    @Test
    fun `fromHex round-trips every colour, case-insensitively`() {
        for (color in HighlightColor.entries) {
            assertThat(HighlightColor.fromHex(color.serverHex)).isEqualTo(color)
            assertThat(HighlightColor.fromHex(color.serverHex.lowercase())).isEqualTo(color)
        }
    }

    @Test
    fun `fromHex defaults to yellow for unknown or null`() {
        assertThat(HighlightColor.fromHex(null)).isEqualTo(HighlightColor.YELLOW)
        assertThat(HighlightColor.fromHex("#123456")).isEqualTo(HighlightColor.YELLOW)
    }

    @Test
    fun `argb values are fully opaque`() {
        for (color in HighlightColor.entries) {
            assertThat(color.argb ushr 24).isEqualTo(0xFF)
        }
    }
}
