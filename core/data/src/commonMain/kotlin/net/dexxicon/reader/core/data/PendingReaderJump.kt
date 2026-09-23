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
 * parameters. Deliberately *not* written to the saved reading position: jumping to a highlight
 * to look at it shouldn't move where the book resumes, or sync that to the server, unless the
 * user then actually reads on from there.
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
