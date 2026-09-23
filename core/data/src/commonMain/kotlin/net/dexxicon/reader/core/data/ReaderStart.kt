package net.dexxicon.reader.core.data

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dexxicon.reader.core.model.ReadingProgress

/**
 * issues #274/#275 — where an EPUB reader should open, worked out without the publication itself:
 * the first usable of a Readium [locatorJson], the reading-order index a foreign EPUB CFI points
 * into ([spineIndex]), then a whole-book [progression] (0–1). Each platform's reader resolves it
 * against the opened publication (Android in `EpubReaderViewModel`, iOS in Swift's
 * `EpubReaderViewController`); nothing usable means the start of the book.
 */
data class ReaderStart(
    val locatorJson: String?,
    val spineIndex: Int?,
    val progression: Double?,
) {
    val isEmpty: Boolean get() = locatorJson == null && spineIndex == null && progression == null

    companion object {
        val BookStart = ReaderStart(null, null, null)
    }
}

/** issue #274 — a tapped highlight's jump as a [ReaderStart]: a BookOrbit CFI opens its chapter. */
fun ReaderJumpTarget.toStart(): ReaderStart =
    ReaderStart(locatorJson, cfi?.let(::cfiSpineIndex), progression?.takeIf { it > 0.0 })

/**
 * issue #275 — where to reopen a book from its progress row. The saved locator, while it still
 * agrees with the row's percent; otherwise the percent alone. They disagree when the percent
 * changed without the reader — seeded from the server's "continue" list (no locator at all),
 * adopted from the server by a sync, or reset by "Mark as unread" — and then the percent is the
 * newer truth. Before this the reader reopened at the stale locator (or the start of the book),
 * and its first save pushed that older position back over the server's.
 */
fun ReadingProgress?.resumeStart(): ReaderStart {
    if (this == null) return ReaderStart.BookStart
    val saved = locator
    val rowPercent = percent
    val savedAt = saved?.let(::totalProgressionOf)
    val agrees = saved != null &&
        (rowPercent == null || savedAt == null || abs(savedAt - rowPercent) <= RESUME_AGREEMENT)
    return if (agrees) {
        ReaderStart(saved, null, null)
    } else {
        ReaderStart(null, null, rowPercent?.takeIf { it > 0.0 })
    }
}

/** Same threshold [ReadingProgressRepository]'s sync uses to call two percentages different. */
private const val RESUME_AGREEMENT = 0.005

private fun totalProgressionOf(locatorJson: String): Double? = runCatching {
    Json.parseToJsonElement(locatorJson).jsonObject["locations"]?.jsonObject
        ?.get("totalProgression")?.jsonPrimitive?.doubleOrNull
}.getOrNull()
