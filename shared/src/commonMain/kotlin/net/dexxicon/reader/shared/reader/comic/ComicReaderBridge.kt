package net.dexxicon.reader.shared.reader.comic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress

/**
 * The iOS comic reader's native-authoritative state (issue #183 Phase 4) — same shape as
 * [net.dexxicon.reader.shared.reader.pdf.PdfReaderNativeState], minus a locator string: a
 * comic page has no real Readium `Locator` to round-trip (the native pager isn't Readium-
 * backed at all — see `ComicPagerViewController`'s own doc comment), so the page number
 * itself is the only position state that needs to cross the bridge.
 */
data class ComicReaderNativeState(
    val screenState: ComicReaderUiState,
    val currentPage: Int,
)

/**
 * Commands the shared chrome sends back to the real Swift pager. [submitPreferences] mirrors
 * [net.dexxicon.reader.shared.reader.pdf.PdfReaderActions]'s own field — same push-on-change
 * mechanism ([net.dexxicon.reader.shared.MainViewController]'s `ComicReaderViewController()`
 * calls it from a `LaunchedEffect(preferences)`) — but where PDF/EPUB translate it into a
 * Readium engine preferences object, the comic pager isn't Readium-backed at all: Swift reads
 * `tapNavigation`/`swipeSensitivity`/`comicRightToLeft` straight off the same
 * `ReaderDisplayPreferences` every other reader already shares, to drive its own gesture/
 * edge-tap logic.
 */
data class ComicReaderActions(
    val goToPage: (Int) -> Unit,
    val submitPreferences: (ReaderDisplayPreferences) -> Unit,
)

/**
 * Non-`suspend`, closure-based bridge to [ReadingProgressRepository] for the iOS comic
 * reader — same shape as [net.dexxicon.reader.shared.reader.pdf.PdfProgressBridge]. Comics
 * had no iOS position save/resume at all before Phase 4; this closes that gap the same way
 * Phase 2/3 did for EPUB/PDF.
 */
class ComicProgressBridge(
    private val progressRepository: ReadingProgressRepository,
    private val scope: CoroutineScope,
) {
    /** The page (1-based) a fresh pager should just open to, already resolved against
     *  whichever of the local/remote position is further along. Null means "start at 1". */
    fun initialPage(
        serverId: String,
        bookId: String,
        digestUrl: String?,
        onResolved: (Int?) -> Unit,
    ) {
        scope.launch {
            val localJson = progressRepository.get(serverId, bookId)?.locator
            val localPercent = localJson?.let(::progressionFromLocatorJson)
            val localPage = localJson?.let(::pageFromLocatorJson)
            val remote = runCatching {
                progressRepository.nativeRemoteAhead(serverId, bookId, ContentFormat.COMIC, digestUrl, localPercent)
            }.getOrNull()
            val resolved = remote?.page ?: localPage
            withContext(Dispatchers.Main) { onResolved(resolved) }
        }
    }

    /** Saves the current position — fire-and-forget, same debounced-caller contract as
     *  [net.dexxicon.reader.shared.reader.pdf.PdfProgressBridge.save]. */
    fun save(serverId: String, bookId: String, page: Int, percent: Double) {
        scope.launch {
            progressRepository.save(
                ReadingProgress(
                    serverId = serverId,
                    bookId = bookId,
                    percent = percent,
                    locator = comicLocatorJsonForPage(page, percent),
                ),
            )
        }
    }
}

/** A placeholder-href, page-only Readium `Locator` JSON for a comic page — same shape
 * [net.dexxicon.reader.core.data.ReadingProgressRepository]'s own (private) `locatorAtPage`
 * builds for non-PDF rows (`type: image/jpeg`), so a comic's saved progress round-trips
 * through the exact same cross-device sync path PDF/EPUB already use. */
internal fun comicLocatorJsonForPage(page: Int, percent: Double): String = buildJsonObject {
    put("href", "sync")
    put("type", "image/jpeg")
    putJsonObject("locations") {
        put("position", page)
        put("totalProgression", percent.coerceIn(0.0, 1.0))
    }
}.toString()

/** Same shape as [net.dexxicon.reader.shared.reader.pdf.pageFromLocatorJson] — duplicated
 * per this package's own convention (see that function's doc comment) rather than shared. */
internal fun pageFromLocatorJson(json: String): Int? = runCatching {
    Json.parseToJsonElement(json).jsonObject["locations"]?.jsonObject?.get("position")?.jsonPrimitive?.int
}.getOrNull()

internal fun progressionFromLocatorJson(json: String): Double? = runCatching {
    Json.parseToJsonElement(json).jsonObject["locations"]?.jsonObject?.get("totalProgression")?.jsonPrimitive?.double
}.getOrNull()
