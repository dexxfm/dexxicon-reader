package net.dexxicon.reader.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Issue #266 — the Highlights list's supporting pieces. */
class HighlightsSupportTest {

    @Test
    fun `cfi spine index is the itemref step after the spine`() {
        assertThat(cfiSpineIndex("epubcfi(/6/14!/4/2/1:0)")).isEqualTo(6)
        assertThat(cfiSpineIndex("epubcfi(/6/2!/4/1:12)")).isEqualTo(0)
        assertThat(cfiSpineIndex("epubcfi(/6/8[chap03]!/4/2,/1:0,/1:40)")).isEqualTo(3)
    }

    @Test
    fun `malformed cfis have no spine index`() {
        assertThat(cfiSpineIndex("")).isNull()
        assertThat(cfiSpineIndex("epubcfi(/6)")).isNull()
        assertThat(cfiSpineIndex("epubcfi(/6/7!/4)")).isNull() // odd steps are text, not elements
        assertThat(cfiSpineIndex("{\"href\":\"x\"}")).isNull()
    }

    @Test
    fun `server times parse with or without a zone`() {
        // BookOrbit: an instant.
        assertThat(parseServerTime("2026-09-20T14:03:11.000Z")).isEqualTo(1_789_912_991_000L)
        // Grimmory: a zoneless LocalDateTime, read as UTC.
        assertThat(parseServerTime("2026-09-20T14:03:11")).isEqualTo(1_789_912_991_000L)
        assertThat(parseServerTime("2026-09-20T16:03:11+02:00")).isEqualTo(1_789_912_991_000L)
        assertThat(parseServerTime(null)).isNull()
        assertThat(parseServerTime("yesterday")).isNull()
    }

    @Test
    fun `a pending jump applies to one open of that book only`() {
        val target = ReaderJumpTarget(locatorJson = null, cfi = "epubcfi(/6/4!/2)", progression = null)
        PendingReaderJump.set("s", "b", target)
        assertThat(PendingReaderJump.take("s", "other")).isNull()
        assertThat(PendingReaderJump.take("s", "b")).isEqualTo(target)
        assertThat(PendingReaderJump.take("s", "b")).isNull()
    }

    @Test
    fun `a jump's landing spot isn't saved, the first real move is`() {
        val hold = JumpPositionHold(active = true)
        assertThat(hold.shouldSave("c3.xhtml", 0.0)).isFalse() // where the jump landed
        assertThat(hold.shouldSave("c3.xhtml#x", 0.00001)).isFalse() // same spot, re-reported
        assertThat(hold.shouldSave("c3.xhtml", 0.2)).isTrue() // turned a page
        assertThat(hold.shouldSave("c3.xhtml", 0.0)).isTrue() // and back — normal saving now
    }

    @Test
    fun `without a jump every position is saved`() {
        assertThat(JumpPositionHold(active = false).shouldSave("c1.xhtml", 0.0)).isTrue()
    }
}
