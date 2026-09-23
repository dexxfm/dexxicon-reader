package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadingProgressTest {

    private fun progress(percent: Double?) =
        ReadingProgress(serverId = "s", bookId = "b", percent = percent)

    @Test
    fun `key is serverId and bookId`() {
        assertThat(progress(0.5).key).isEqualTo("s::b")
    }

    @Test
    fun `isInProgress is true only for a started, unfinished book`() {
        assertThat(progress(null).isInProgress).isFalse()
        assertThat(progress(0.0).isInProgress).isFalse()
        assertThat(progress(0.01).isInProgress).isTrue()
        assertThat(progress(0.5).isInProgress).isTrue()
        assertThat(progress(0.98).isInProgress).isTrue()
        assertThat(progress(0.99).isInProgress).isFalse()
        assertThat(progress(1.0).isInProgress).isFalse()
    }

    @Test
    fun `a book hidden from Continue stays hidden until it's read further`() {
        val hidden = progress(0.4).copy(hiddenAtPercent = 0.4)
        assertThat(progress(0.4).isHiddenFromContinue).isFalse()
        assertThat(hidden.isHiddenFromContinue).isTrue()
        // server rounding noise doesn't bring it back…
        assertThat(hidden.copy(percent = 0.403).isHiddenFromContinue).isTrue()
        // …real reading, here or on another device, does.
        assertThat(hidden.copy(percent = 0.45).isHiddenFromContinue).isFalse()
    }
}
