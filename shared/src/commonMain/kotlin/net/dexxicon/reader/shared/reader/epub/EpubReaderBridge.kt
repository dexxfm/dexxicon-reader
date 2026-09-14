package net.dexxicon.reader.shared.reader.epub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.ReadingProgress

/**
 * The iOS EPUB reader's native-authoritative state (issue #183 Phase 2) — everything only the
 * real Readium Swift navigator knows, pushed into `:shared`'s Compose chrome the same way
 * [net.dexxicon.reader.shared.player.PlayerUiSnapshot] already is for the audiobook player.
 * Android's equivalent native embed (`feature/reader-epub`'s `EpubReaderScreen.kt`) needs no
 * such push — it's Kotlin too, so it reads the real `EpubNavigatorFragment`'s state directly —
 * this type exists only because Swift can't be that Kotlin code.
 */
data class EpubReaderNativeState(
    val screenState: EpubReaderUiState,
    /** A Readium `Locator`, serialised via `jsonString()`/`Locator(jsonString:)` (issue #183 —
     * confirmed symmetric with Android's `Locator.toJSON()`/`Locator.fromJSON()`). Null until
     * the navigator reports its first position. */
    val currentLocatorJson: String?,
)

/**
 * Commands the shared chrome sends back to the real Swift navigator — the answer to every
 * `onGoTo*`/`onUpdatePreferences` callback [net.dexxicon.reader.shared.reader.epub.EpubReaderScreen]
 * exposes that needs a real `Locator`/`Publication`/navigator call. Portable [Bookmark]/
 * [Highlight]/[TocEntry] objects cross the boundary directly (issue #183's "closure-boxing"
 * pattern already proven for [net.dexxicon.reader.shared.player.PlayerActions] etc.) rather
 * than plain strings, so Swift can read `.locatorJson`/`.isForeign`/`.ref` itself — the
 * foreign-bookmark-by-title-match branch Android's own embed needs stays native either way.
 */
data class EpubReaderActions(
    val goToBookmark: (Bookmark) -> Unit,
    val goToHighlight: (Highlight) -> Unit,
    val goToToc: (TocEntry) -> Unit,
    val jumpToRemoteResume: () -> Unit,
    /** Applies [ReaderDisplayPreferences] to the real navigator — the Swift-side equivalent
     * of `EpubPreferencesMapping.kt`'s `toEpubPreferences()`, since Readium Swift's own
     * `EPUBPreferences` isn't a KMP type. */
    val submitPreferences: (ReaderDisplayPreferences) -> Unit,
    /** Called whenever the collected highlights list changes, so the native navigator can
     * rebuild and re-apply its `Decoration`s — the Swift-side mirror of Android's own
     * `LaunchedEffect(navigator, highlights) { nav.applyDecorations(...) }`, needed here only
     * because Swift can't collect a Kotlin `Flow` directly (untested in this project; see
     * [net.dexxicon.reader.shared.reader.AudiobookProgressSync]'s doc comment for the same
     * caution applied to `suspend` functions). */
    val applyHighlights: (List<Highlight>) -> Unit,
)

/**
 * Non-`suspend`, closure-based bridge to [ReadingProgressRepository] for the iOS EPUB reader —
 * the exact shape [net.dexxicon.reader.shared.reader.AudiobookProgressSync] already
 * established and its doc comment explains: Swift calling a Kotlin `suspend` function directly
 * is untested in this project, so every entry point here is a plain function plus a completion
 * closure instead. Unlike that class, this doesn't need its own remote-sync reimplementation —
 * [ReadingProgressRepository] already has a portable `Locator`-JSON-based API (issue #126),
 * so this is a thin adapter, not a parallel implementation.
 */
class EpubProgressBridge(
    private val progressRepository: ReadingProgressRepository,
    private val scope: CoroutineScope,
) {
    /** The saved position (Readium `Locator` JSON), if any — the initial location a fresh
     * `EPUBNavigatorViewController` should open to. */
    fun initialLocatorJson(serverId: String, bookId: String, onResolved: (String?) -> Unit) {
        scope.launch {
            val json = progressRepository.get(serverId, bookId)?.locator
            withContext(Dispatchers.Main) { onResolved(json) }
        }
    }

    /** A resume percentage the server has that's meaningfully ahead of [localPercent] — same
     * "Continue from NN% (synced)" banner Android's reader shows. Null on any failure. */
    fun remoteResumePercent(
        serverId: String,
        bookId: String,
        digestUrl: String?,
        localPercent: Double?,
        onResolved: (Double?) -> Unit,
    ) {
        scope.launch {
            val percent = runCatching {
                progressRepository.remoteResumePercent(serverId, bookId, ContentFormat.EPUB, digestUrl, localPercent)
            }.getOrNull()
            withContext(Dispatchers.Main) { onResolved(percent) }
        }
    }

    /** Saves the current position — fire-and-forget, called on every (debounced, Swift-side)
     * `locationDidChange`, same cadence as Android's `locatorUpdates.debounce(1_500)`. */
    fun save(serverId: String, bookId: String, locatorJson: String, percent: Double?) {
        scope.launch {
            progressRepository.save(
                ReadingProgress(serverId = serverId, bookId = bookId, percent = percent, locator = locatorJson),
            )
        }
    }
}

/** A Readium `Locator` JSON string's `locations.totalProgression` (0.0–1.0) — pure string
 * parsing so the iOS embed can compute [Bookmark]-matching progress without a real `Locator`
 * type, the same field Android's own code already reads this way (e.g.
 * [net.dexxicon.reader.core.data.ReadingProgressRepository]'s `pageOf`). */
internal fun progressionFromLocatorJson(json: String): Double? = runCatching {
    Json.parseToJsonElement(json).jsonObject["locations"]?.jsonObject?.get("totalProgression")?.jsonPrimitive?.double
}.getOrNull()

/** A Readium `Locator` JSON string's top-level `title` — the chapter/section title a new
 * [Bookmark] is labelled with, mirroring Android's `locator.title.orEmpty()`. */
internal fun titleFromLocatorJson(json: String): String? = runCatching {
    Json.parseToJsonElement(json).jsonObject["title"]?.jsonPrimitive?.contentOrNull
}.getOrNull()
