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
}
