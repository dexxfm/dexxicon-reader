package net.dexxicon.reader.shared

import io.ktor.http.Url
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.Chapter
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Hand-off point from `:shared`'s Compose UI to a genuinely native, per-platform reading
 * screen (issue #99 — see the "iOS Reading Support" proposal for why: no KMP reading engine
 * fits what this app already depends on, so reading is native Swift on iOS, using Readium's
 * own Swift Toolkit, while Android keeps its existing, untouched native reader stack).
 *
 * Plain data crosses this boundary — [url] and [authHeader] are already resolved (via
 * [net.dexxicon.reader.shared.di.AppContainer.authHeaderProvider]) by the caller, so the
 * platform side never needs to reach back into `:shared`'s auth/network layer just to open a
 * book. [authHeader] is null when the acquisition needs no `Authorization` header at all (an
 * open-access acquisition, or a server type
 * [net.dexxicon.reader.core.network.AuthHeaderProvider] doesn't recognize).
 *
 * This is a plain Kotlin function type, not an interop object, and deliberately so: Kotlin/
 * Native can't call arbitrary third-party Swift code directly — only Objective-C/C, per
 * Kotlin's own interop docs — so a Swift-side reader can't be instantiated *from* Kotlin
 * without a real `@objc` + cinterop bridge. The natural, already-supported direction is the
 * other way: Swift implements this closure when it constructs `MainViewController`, exactly
 * like every other callback (`onOpenBook`, `onBack`, …) already crossing into this shared UI
 * — and, once invoked, Swift's own `UINavigationController` pushes a fully native screen,
 * entirely outside Compose's render tree.
 *
 * [isManga] (issue #108) mirrors Android's `ComicReaderViewModel.mangaGenre`: true when the
 * book's own genre/category tags mention "manga", so the platform reader can pick the right
 * reading direction (and, on iOS, which edge means "next page") without needing its own path
 * back into `:shared`'s catalog data just to check a genre tag.
 *
 * [audiobook] (issue #114) is non-null only for [ContentFormat.AUDIOBOOK] — bundled into one
 * small data class rather than four more positional parameters most other formats would never
 * use, the same reasoning [AudiobookLaunchInfo] itself documents.
 */
typealias OnOpenReader = (
    serverId: String,
    bookId: String,
    format: ContentFormat,
    url: String,
    authHeader: String?,
    isManga: Boolean,
    audiobook: AudiobookLaunchInfo?,
) -> Unit

/**
 * Metadata iOS's native audiobook player needs that no other reader does — Now Playing
 * info (title/author/cover) and chapter navigation — grouped here instead of growing
 * [OnOpenReader]'s own parameter list with fields only one format ever reads. Mirrors exactly
 * what Android's `PlayerViewModel.load()` already resolves from the same `BookDetail`/
 * `BookDetail.audio`, so both platforms start a book with identical metadata.
 */
data class AudiobookLaunchInfo(
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val chapters: List<Chapter>,
)

/**
 * Resolves [detail] into an [OnOpenReader] call — the acquisition URL, a fresh auth header
 * (via [AppContainer.authHeaderProvider]), the manga-genre check, and (audiobooks only) the
 * launch metadata [AudiobookLaunchInfo] needs. One implementation shared by every tap-to-read
 * site (Stage B's Book Detail "Read"/"Play" button, Stage C's Home "Continue reading/listening"
 * shelves) rather than each resolving the same three things inline — [serverId]/[bookId] are
 * taken separately from [detail] since a merged-copies tap (Book Detail's multi-server picker)
 * launches a specific copy while [detail]'s own acquisition/category/audio data is shared
 * across every copy of the same title.
 */
fun AppContainer.openReader(
    detail: BookDetail,
    serverId: String,
    bookId: String,
    onOpenReader: OnOpenReader,
) {
    val acquisition = detail.acquisitions.firstOrNull { it.format == detail.summary.format }
        ?: detail.primaryAcquisition
        ?: return
    val header = authHeaderProvider.authHeader(Url(acquisition.href))
    // issue #108 — same genre-tag check as Android's ComicReaderViewModel.mangaGenre.
    val isManga = detail.categories.any { it.contains("manga", ignoreCase = true) }
    // issue #114 — same metadata Android's PlayerViewModel.load() resolves from this exact
    // BookDetail/BookDetail.audio.
    val audiobook = detail.audio
        ?.takeIf { detail.summary.format == ContentFormat.AUDIOBOOK }
        ?.let {
            AudiobookLaunchInfo(
                title = detail.summary.title,
                author = detail.summary.authorLine.takeIf { it.isNotBlank() },
                coverUrl = detail.summary.coverUrl,
                durationMs = it.durationMs,
                chapters = it.chapters,
            )
        }
    onOpenReader(serverId, bookId, detail.summary.format, acquisition.href, header, isManga, audiobook)
}
