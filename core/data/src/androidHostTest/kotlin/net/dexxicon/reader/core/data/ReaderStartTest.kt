package net.dexxicon.reader.core.data

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.model.ReadingProgress
import org.junit.Test

/** Issues #274/#275 — where a reader opens. */
class ReaderStartTest {

    private fun locatorAt(total: Double) =
        """{"href":"OEBPS/c2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5,"totalProgression":$total}}"""

    private fun row(percent: Double?, locator: String?) =
        ReadingProgress(serverId = "s", bookId = "b", percent = percent, locator = locator)

    @Test
    fun `a saved locator that matches the row's percent is where the book resumes`() {
        val start = row(0.45, locatorAt(0.45)).resumeStart()
        assertThat(start).isEqualTo(ReaderStart(locatorAt(0.45), null, null))
    }

    @Test
    fun `a row seeded from the server, with no locator, resumes at its percent`() {
        assertThat(row(0.45, null).resumeStart()).isEqualTo(ReaderStart(null, null, 0.45))
    }

    @Test
    fun `a percent adopted from the server since the locator was saved wins over the locator`() {
        assertThat(row(0.45, locatorAt(0.10)).resumeStart()).isEqualTo(ReaderStart(null, null, 0.45))
    }

    @Test
    fun `a book marked unread reopens at its start, not the old locator`() {
        assertThat(row(0.0, locatorAt(0.60)).resumeStart().isEmpty).isTrue()
    }

    @Test
    fun `no row, or a row without a position, opens at the start`() {
        assertThat((null as ReadingProgress?).resumeStart().isEmpty).isTrue()
        assertThat(row(null, null).resumeStart().isEmpty).isTrue()
    }

    @Test
    fun `a locator with no progression of its own, or a row with no percent, keeps the locator`() {
        val bare = """{"href":"OEBPS/c2.xhtml","type":"application/xhtml+xml","locations":{}}"""
        assertThat(row(0.3, bare).resumeStart().locatorJson).isEqualTo(bare)
        assertThat(row(null, locatorAt(0.3)).resumeStart().locatorJson).isEqualTo(locatorAt(0.3))
    }

    @Test
    fun `a BookOrbit CFI highlight jumps to its text in its chapter`() {
        val start = ReaderJumpTarget(locatorJson = null, cfi = "epubcfi(/6/6!/4/2/1:0)", progression = 0.0, text = "ash fell")
            .toStart()
        assertThat(start).isEqualTo(ReaderStart(null, 2, null, "ash fell"))
    }

    @Test
    fun `text only rides along with a chapter`() {
        assertThat(ReaderJumpTarget(null, null, 0.3, text = "ash fell").toStart()).isEqualTo(ReaderStart(null, null, 0.3, null))
    }

    @Test
    fun `a jump with nothing usable is empty, so the reader falls back to resuming`() {
        assertThat(ReaderJumpTarget(null, null, 0.0).toStart().isEmpty).isTrue()
    }
}
