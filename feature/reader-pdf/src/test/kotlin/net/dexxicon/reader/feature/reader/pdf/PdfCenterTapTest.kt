package net.dexxicon.reader.feature.reader.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfCenterTapTest {
    @Test
    fun recognizesCenterAndExcludesEdges() {
        assertThat(isCenterTap(279f, 1000)).isFalse()
        assertThat(isCenterTap(280f, 1000)).isTrue()
        assertThat(isCenterTap(500f, 1000)).isTrue()
        assertThat(isCenterTap(720f, 1000)).isTrue()
        assertThat(isCenterTap(721f, 1000)).isFalse()
    }

    @Test
    fun unmeasuredViewDoesNotHandleTap() {
        assertThat(isCenterTap(0f, 0)).isFalse()
    }
}
