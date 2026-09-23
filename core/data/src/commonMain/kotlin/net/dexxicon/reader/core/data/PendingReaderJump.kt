package net.dexxicon.reader.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * issue #266 — where a reader should open for one book, just this once (a tapped highlight).
 * Whichever is usable wins, in order: a Readium [locatorJson] (highlights made in this app, or
 * synced from Grimmory), a foreign EPUB [cfi] (BookOrbit's web reader), resolved to its chapter,
 * then a bare [progression].
 */
data class ReaderJumpTarget(
    val locatorJson: String?,
    val cfi: String?,
    val progression: Double?,
)

/**
 * issue #266 — a one-shot "open this book here" note from the Highlights screen to the reader.
 * Process-global on purpose: Android's reader lives in its own Activity/Hilt graph and iOS's in
 * Swift, so neither can be handed it through [net.dexxicon.reader.shared.OnOpenReader]'s fixed
 * parameters. Looking at a highlight shouldn't move where the book resumes (or sync that to the
 * server): the reader holds off saving its position until the user moves away from where it
 * landed — see [OpeningPositionHold].
 */
object PendingReaderJump {
    private val pending = MutableStateFlow<Map<String, ReaderJumpTarget>>(emptyMap())

    fun set(serverId: String, bookId: String, target: ReaderJumpTarget) {
        pending.update { it + (key(serverId, bookId) to target) }
    }

    /** The jump for this book, if any — removed as it's read, so it applies to one open only. */
    fun take(serverId: String, bookId: String): ReaderJumpTarget? {
        var taken: ReaderJumpTarget? = null
        pending.update { current ->
            taken = current[key(serverId, bookId)]
            current - key(serverId, bookId)
        }
        return taken
    }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"
}

/**
 * issue #266 — the reading-order (spine) index an EPUB CFI points into, e.g.
 * `epubcfi(/6/14!/4/2/1:0)` → 6. The step after the package document's spine (`/6`) is the
 * itemref, numbered in CFI's even-numbers-only scheme: `/2` is the first (index 0). Enough to
 * open the right chapter; the in-chapter part of the path needs the rendered document itself.
 * Null for anything that isn't a CFI of that shape.
 */
fun cfiSpineIndex(cfi: String): Int? {
    val path = cfi.trim().removePrefix("epubcfi(").removeSuffix(")")
    val steps = path.substringBefore('!').split('/').filter { it.isNotEmpty() }
    if (steps.size < 2) return null
    val itemref = steps[1].substringBefore('[').toIntOrNull() ?: return null
    if (itemref < 2 || itemref % 2 != 0) return null
    return itemref / 2 - 1
}

/**
 * "Don't save the position a reader merely opened at." A reader reports its position as soon as
 * it opens, and saving that report pushes it to the server too:
 *  - issue #266 — after a highlight jump, that made the highlight's spot where the book resumes;
 *  - issue #275 — when the reader opened somewhere older than the server's position (behind a
 *    "Continue from NN%" offer the user dismissed, say), it pushed that older spot over the
 *    server's, losing the reading position on every other device.
 * Opening a book isn't reading it, so [shouldSave] skips the first position reported, and any
 * repeat of that exact spot; the first genuinely different one (a page turn either way, a
 * scroll, a TOC jump, taking the "Continue from" offer) ends the hold and saves normally from
 * then on. [active] is false only where a reader deliberately saves its landing spot.
 */
class OpeningPositionHold(private var active: Boolean = true) {
    private var landed: Pair<String, Double>? = null

    /** [href] is the resource, [progression] the position within it. */
    fun shouldSave(href: String, progression: Double): Boolean {
        if (!active) return true
        val here = href.substringBefore('#') to progression
        val first = landed
        if (first == null) {
            landed = here
            return false
        }
        if (first.first == here.first && kotlin.math.abs(first.second - here.second) < SAME_SPOT) return false
        active = false
        return true
    }

    private companion object {
        /** Readium re-reports the same spot with float noise; any real move is far bigger. */
        const val SAME_SPOT = 1e-4
    }
}
