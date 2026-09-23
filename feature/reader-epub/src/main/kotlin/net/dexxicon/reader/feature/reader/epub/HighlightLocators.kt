package net.dexxicon.reader.feature.reader.epub

import net.dexxicon.reader.core.data.cfiSpineIndex
import net.dexxicon.reader.core.model.Highlight
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * issue #278 — a highlight's Readium [Locator], to paint it or go to it. A highlight made in
 * BookOrbit's web reader has only an EPUB CFI (its stored locator is `{}`), so it gets its
 * chapter's locator, from [cfiSpineIndex], carrying the highlighted text: Readium finds a
 * locator's exact range from that text quote. Null when neither is usable.
 */
internal fun Publication.locatorOf(highlight: Highlight): Locator? =
    runCatching { Locator.fromJSON(JSONObject(highlight.locatorJson)) }.getOrNull()
        ?: highlight.cfi?.let(::cfiSpineIndex)?.let { chapterLocator(it, highlight.text) }

/** issues #274/#278 — the reading-order item at [spineIndex], pinned to [text] when there is one. */
internal fun Publication.chapterLocator(spineIndex: Int, text: String?): Locator? {
    val chapter = readingOrder.getOrNull(spineIndex)?.let(::locatorFromLink) ?: return null
    return if (text.isNullOrBlank()) chapter else chapter.copy(text = Locator.Text(highlight = text))
}
