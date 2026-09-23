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
    fun `fromHex defaults to yellow for null or malformed`() {
        assertThat(HighlightColor.fromHex(null)).isEqualTo(HighlightColor.YELLOW)
        assertThat(HighlightColor.fromHex("yellowish")).isEqualTo(HighlightColor.YELLOW)
        assertThat(HighlightColor.fromHex("#12345")).isEqualTo(HighlightColor.YELLOW)
    }

    @Test
    fun `other palettes map to the nearest of ours (issue 266)`() {
        // BookOrbit's ANNOTATION_HIGHLIGHT_COLORS
        assertThat(HighlightColor.fromHex("#4ADE80")).isEqualTo(HighlightColor.GREEN)
        assertThat(HighlightColor.fromHex("#38BDF8")).isEqualTo(HighlightColor.BLUE)
        assertThat(HighlightColor.fromHex("#C084FC")).isEqualTo(HighlightColor.PURPLE)
        // KOReader's exact yellow
        assertThat(HighlightColor.fromHex("#FFFF33")).isEqualTo(HighlightColor.YELLOW)
    }

    @Test
    fun `argb values are fully opaque`() {
        for (color in HighlightColor.entries) {
            assertThat(color.argb ushr 24).isEqualTo(0xFF)
        }
    }
}
