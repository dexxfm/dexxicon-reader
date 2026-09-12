package net.dexxicon.reader.core.data.media

import android.net.Uri
import kotlinx.coroutines.flow.first
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.media.AudiobookCard
import net.dexxicon.reader.core.media.LibraryNode
import net.dexxicon.reader.core.media.MediaLibraryContentSource
import net.dexxicon.reader.core.media.MediaPage
import net.dexxicon.reader.core.media.PlayableAudiobook
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ServerType
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaLibraryContentSourceImpl @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val progressRepository: ReadingProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val serverRepository: ServerRepository,
    private val tokenManager: TokenManager,
) : MediaLibraryContentSource {

    private val audioOnly = setOf(ContentFormat.AUDIOBOOK)

    private companion object {
        const val SOURCE_PAGE_SIZE = 60
        const val MAX_SOURCE_PAGES = 10
        const val MAX_AUDIOBOOKS = 200
    }

    override suspend fun continueListening(): List<AudiobookCard> =
        progressRepository.observeInProgress().first()
            .filter { it.format == ContentFormat.AUDIOBOOK && !it.title.isNullOrBlank() }
            .map { it.toCard() }

    override suspend fun downloaded(): List<AudiobookCard> =
        downloadRepository.downloads.first()
            .filter { it.format == ContentFormat.AUDIOBOOK && it.status == DownloadStatus.DONE }
            .map {
                AudiobookCard(
                    mediaId = it.key,
                    title = it.title,
                    author = it.authorLine.takeIf { line -> line.isNotBlank() },
                    artworkUri = it.coverUrl,
                    progress = progressRepository.get(it.serverId, it.bookId)?.percent,
                )
            }

    override suspend fun libraries(): List<LibraryNode> =
        serverRepository.servers.first()
            .filter { it.type == ServerType.BOOKORBIT || it.type == ServerType.GRIMMORY }
            .map { LibraryNode(id = "lib:${it.id}", title = it.displayName) }

    /**
     * Audiobooks are a small fraction of most catalogs and the servers (BookOrbit) don't
     * all filter by format server-side, so a single fetched page is often all books, no
     * audio. Auto-page past those — capped — and return one flat list; the car doesn't
     * scroll hundreds of audiobooks so [page] > 0 is empty.
     */
    override suspend fun audiobooks(
        serverId: String?,
        page: Int,
        pageSize: Int,
    ): MediaPage<AudiobookCard> {
        if (page > 0) return MediaPage(emptyList(), hasMore = false)

        val collected = mutableListOf<AudiobookCard>()
        var sourcePage = 0
        var hasMore = true
        while (hasMore && sourcePage < MAX_SOURCE_PAGES && collected.size < MAX_AUDIOBOOKS) {
            val fetch = fetchAudioPage(serverId, sourcePage, SOURCE_PAGE_SIZE)
            if (fetch.authExpired && collected.isEmpty()) {
                return MediaPage(emptyList(), hasMore = false, authExpired = true)
            }
            collected += fetch.cards
            hasMore = fetch.hasMore
            sourcePage++
        }
        return MediaPage(collected.distinctBy { it.mediaId }.take(MAX_AUDIOBOOKS), hasMore = false)
    }

    private class AudioFetch(
        val cards: List<AudiobookCard>,
        val hasMore: Boolean,
        val authExpired: Boolean = false,
    )

    private suspend fun fetchAudioPage(serverId: String?, page: Int, pageSize: Int): AudioFetch {
        if (serverId == null) {
            return when (
                val r = catalogRepository.allBooks(null, BookSort.RECENT, page, pageSize, audioOnly)
            ) {
                is Outcome.Failure -> AudioFetch(emptyList(), false, r.error is DexxiconError.Unauthorized)
                is Outcome.Success -> AudioFetch(
                    r.value.books.map { book ->
                        val copy = book.primary
                        AudiobookCard(
                            mediaId = "${copy.serverId}::${copy.bookId}",
                            title = book.title,
                            author = book.authorLine.takeIf { it.isNotBlank() },
                            artworkUri = book.coverUrl,
                        )
                    },
                    r.value.hasMore,
                )
            }
        }
        return when (
            val r = catalogRepository.books(serverId, null, null, BookSort.RECENT, page, pageSize, audioOnly)
        ) {
            is Outcome.Failure -> AudioFetch(emptyList(), false, r.error is DexxiconError.Unauthorized)
            is Outcome.Success -> AudioFetch(
                r.value.books.map {
                    AudiobookCard(
                        mediaId = "${it.serverId}::${it.id}",
                        title = it.title,
                        author = it.authorLine.takeIf { line -> line.isNotBlank() },
                        artworkUri = it.coverUrl,
                    )
                },
                r.value.hasMore,
            )
        }
    }

    override suspend fun search(query: String): List<AudiobookCard> {
        val result = catalogRepository.allBooks(
            query = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList(),
            sort = BookSort.RECENT,
            page = 0,
            pageSize = 50,
            formats = audioOnly,
        )
        val value = (result as? Outcome.Success)?.value ?: return emptyList()
        return value.books.map { book ->
            val copy = book.primary
            AudiobookCard(
                mediaId = "${copy.serverId}::${copy.bookId}",
                title = book.title,
                author = book.authorLine.takeIf { it.isNotBlank() },
                artworkUri = book.coverUrl,
            )
        }
    }

    override suspend fun lastPlayed(): AudiobookCard? =
        progressRepository.observeAll().first().values
            .filter { it.format == ContentFormat.AUDIOBOOK && !it.title.isNullOrBlank() }
            .maxByOrNull { it.updatedAt }
            ?.toCard()

    override suspend fun resolve(mediaId: String): PlayableAudiobook? {
        val (serverId, bookId) = mediaId.split("::", limit = 2)
            .takeIf { it.size == 2 } ?: return null

        // DownloadRepository.localFile returns a plain path (issue #126 — java.io.File isn't
        // portable), so this Android-only caller wraps it back into a File itself.
        val localFile = downloadRepository.localFile(serverId, bookId)?.let(::File)

        val detail = (catalogRepository.detail(serverId, bookId) as? Outcome.Success)?.value
        // Offline / expired session: a downloaded book is still playable from its local file
        // and the position we last saved — no server round-trip.
        if (detail == null) return localFile?.let { resolveFromLocal(mediaId, serverId, bookId, it) }

        val acquisition = detail.acquisitions.firstOrNull { it.format == ContentFormat.AUDIOBOOK }
            ?: detail.primaryAcquisition
            ?: return localFile?.let { resolveFromLocal(mediaId, serverId, bookId, it) }

        val uri = localFile?.let { Uri.fromFile(it).toString() } ?: acquisition.href
        val durationMs = detail.audio?.durationMs ?: 0L

        // Record the book so the position write-back ([ServicePositionWriter]) has metadata
        // and can push to the server.
        progressRepository.save(
            ReadingProgress(
                serverId = serverId,
                bookId = bookId,
                title = detail.summary.title,
                author = detail.summary.authorLine.takeIf { it.isNotBlank() },
                coverUrl = detail.summary.coverUrl,
                format = ContentFormat.AUDIOBOOK,
                digestUrl = acquisition.href,
            ),
        )

        val localMs = progressRepository.get(serverId, bookId)?.locator
            ?.let { runCatching { JSONObject(it).optLong("position", 0L) }.getOrDefault(0L) }
            ?: 0L
        val startMs = progressRepository.audiobookResumeMs(
            serverId = serverId,
            bookId = bookId,
            digestUrl = acquisition.href,
            durationMs = durationMs,
            localMs = localMs,
        )

        return PlayableAudiobook(
            mediaId = mediaId,
            uri = uri,
            title = detail.summary.title,
            author = detail.summary.authorLine.takeIf { it.isNotBlank() },
            artworkUri = detail.summary.coverUrl,
            startPositionMs = startMs,
            durationMs = durationMs,
            // Local copies can't be cast; a remote stream is cast with the token in the URL
            // (the Cast receiver can't send our Authorization header).
            castUri = if (localFile == null) castUrlFor(serverId, acquisition.href) else null,
            mimeType = castMimeType(acquisition.mediaType),
            chapters = detail.audio?.chapters ?: emptyList(),
        )
    }

    /** A stream URL the Cast receiver can fetch itself (auth as `?token=`), or null. */
    private suspend fun castUrlFor(serverId: String, href: String): String? {
        if (!href.startsWith("http")) return null
        val server: Server = serverRepository.get(serverId) ?: return null
        if (server.authMode != AuthMode.NATIVE && server.authMode != AuthMode.OIDC) return null
        val token = (tokenManager.bearerToken(server) as? Outcome.Success)?.value ?: return null
        val sep = if ('?' in href) '&' else '?'
        return "$href${sep}token=${Uri.encode(token)}"
    }

    private fun castMimeType(mediaType: String?): String? = when {
        mediaType.isNullOrBlank() -> null
        mediaType.contains("m4b", true) || mediaType.contains("m4a", true) ||
            mediaType.contains("mp4", true) || mediaType.contains("aac", true) -> "audio/mp4"
        mediaType.contains("mpeg", true) || mediaType.contains("mp3", true) -> "audio/mpeg"
        mediaType.contains("ogg", true) || mediaType.contains("opus", true) -> "audio/ogg"
        mediaType.contains("flac", true) -> "audio/flac"
        mediaType.startsWith("audio/") -> mediaType
        else -> null
    }

    /** Build a playable straight from the downloaded file + locally-saved metadata/position. */
    private suspend fun resolveFromLocal(
        mediaId: String,
        serverId: String,
        bookId: String,
        file: java.io.File,
    ): PlayableAudiobook {
        val progress = progressRepository.get(serverId, bookId)
        val download = downloadRepository.downloads.first()
            .firstOrNull { it.serverId == serverId && it.bookId == bookId }
        val localMs = progress?.locator
            ?.let { runCatching { JSONObject(it).optLong("position", 0L) }.getOrDefault(0L) }
            ?: 0L
        return PlayableAudiobook(
            mediaId = mediaId,
            uri = Uri.fromFile(file).toString(),
            title = progress?.title ?: download?.title ?: "Audiobook",
            author = (progress?.author ?: download?.authorLine)?.takeIf { it.isNotBlank() },
            artworkUri = progress?.coverUrl ?: download?.coverUrl,
            startPositionMs = localMs,
            durationMs = 0L,
        )
    }

    private fun ReadingProgress.toCard() = AudiobookCard(
        mediaId = key,
        title = title.orEmpty(),
        author = author,
        artworkUri = coverUrl,
        progress = percent,
    )
}
