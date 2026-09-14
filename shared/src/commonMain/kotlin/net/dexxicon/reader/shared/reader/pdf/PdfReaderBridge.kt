package net.dexxicon.reader.shared.reader.pdf

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
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.shared.reader.epub.TocEntry

/**
 * The iOS PDF reader's native-authoritative state (issue #183 Phase 3) — same shape as
 * [net.dexxicon.reader.shared.reader.epub.EpubReaderNativeState]; see that type's doc comment
 * for why this exists only for iOS.
 */
data class PdfReaderNativeState(
    val screenState: PdfReaderUiState,
    /** A Readium `Locator`, serialised via `jsonString()`/`Locator(jsonString:)` — same
     * round-trip EPUB already uses. Null until the navigator reports its first position. */
    val currentLocatorJson: String?,
)

/**
 * Commands the shared chrome sends back to the real Swift navigator. Unlike
 * [net.dexxicon.reader.shared.reader.epub.EpubReaderActions], bookmarks and the page slider
 * both resolve to a plain page number — PDF locators are placeholder-href, page-only JSON
 * (see [pdfLocatorJsonForPage]), so the shared chrome/native-embed glue can extract a page
 * number from a [Bookmark] itself (portable string parsing) and this only needs [goToPage].
 * [goToLocatorJson] is the fallback for a bookmark with no extractable page (e.g. a "foreign"
 * one from a server's web reader), mirroring Android's own `goToBookmark()` fallback.
 */
data class PdfReaderActions(
    val goToPage: (Int) -> Unit,
    val goToLocatorJson: (String) -> Unit,
    val goToToc: (TocEntry) -> Unit,
    val submitPreferences: (ReaderDisplayPreferences) -> Unit,
)

/**
 * Non-`suspend`, closure-based bridge to [ReadingProgressRepository] for the iOS PDF reader —
 * same shape as [net.dexxicon.reader.shared.reader.epub.EpubProgressBridge]. Narrower than
 * that one: PDF has no remote-resume banner, so the "which position wins" decision (local vs.
 * the server's, whichever is further along) happens entirely here rather than being exposed
 * to the chrome — [initialLocatorJson] hands back the position a fresh `PDFNavigatorViewController`
 * should just open to, already resolved.
 */
class PdfProgressBridge(
    private val progressRepository: ReadingProgressRepository,
    private val scope: CoroutineScope,
) {
    /** The locator (as JSON) a fresh PDF navigator should open to. */
    fun initialLocatorJson(
        serverId: String,
        bookId: String,
        digestUrl: String?,
        onResolved: (String?) -> Unit,
    ) {
        scope.launch {
            val localJson = progressRepository.get(serverId, bookId)?.locator
            val localPercent = localJson?.let(::progressionFromLocatorJson)
            val localPage = localJson?.let(::pageFromLocatorJson)
            val remote = runCatching {
                progressRepository.nativeRemoteAhead(serverId, bookId, ContentFormat.PDF, digestUrl, localPercent)
            }.getOrNull()
            val targetPage = remote?.page ?: localPage
            val resolved = targetPage?.let { page ->
                pdfLocatorJsonForPage(page, remote?.percent ?: localPercent ?: 0.0)
            } ?: localJson
            withContext(Dispatchers.Main) { onResolved(resolved) }
        }
    }

    /** Saves the current position — fire-and-forget, called on every (debounced, Swift-side)
     * `locationDidChange`, same cadence as Android's `locatorUpdates.debounce(1_000)`. */
    fun save(serverId: String, bookId: String, locatorJson: String, percent: Double?) {
        scope.launch {
            progressRepository.save(
                ReadingProgress(serverId = serverId, bookId = bookId, percent = percent, locator = locatorJson),
            )
        }
    }
}

/** A placeholder-href, page-only Readium `Locator` JSON — the same shape
 * [net.dexxicon.reader.core.data.ReadingProgressRepository]'s own (private) `locatorAtPage`
 * already builds for PDF/comic rows. The `href` is never read back — the navigator re-resolves
 * the page against the open publication's own (single-item, for a PDF) reading order. */
internal fun pdfLocatorJsonForPage(page: Int, percent: Double): String = buildJsonObject {
    put("href", "sync")
    put("type", "application/pdf")
    putJsonObject("locations") {
        put("position", page)
        put("totalProgression", percent.coerceIn(0.0, 1.0))
    }
}.toString()

/** A Readium `Locator` JSON string's `locations.position` (1-based page) — pure string
 * parsing, mirroring [net.dexxicon.reader.shared.reader.epub.progressionFromLocatorJson]. */
internal fun pageFromLocatorJson(json: String): Int? = runCatching {
    Json.parseToJsonElement(json).jsonObject["locations"]?.jsonObject?.get("position")?.jsonPrimitive?.int
}.getOrNull()

/** Same as [net.dexxicon.reader.shared.reader.epub.progressionFromLocatorJson] — duplicated
 * (rather than shared) since it's a two-line pure function and the epub package is a natural
 * home for EPUB-specific helpers, not a shared-utilities dumping ground. */
internal fun progressionFromLocatorJson(json: String): Double? = runCatching {
    Json.parseToJsonElement(json).jsonObject["locations"]?.jsonObject?.get("totalProgression")?.jsonPrimitive?.double
}.getOrNull()
