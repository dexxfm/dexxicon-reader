package net.dexxicon.reader.core.data.media

import android.net.Uri
import kotlinx.coroutines.flow.first
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaLibraryContentSourceImpl @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val progressRepository: ReadingProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val serverRepository: ServerRepository,
) : MediaLibraryContentSource {

    private val audioOnly = setOf(ContentFormat.AUDIOBOOK)

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

    override suspend fun audiobooks(
        serverId: String?,
        page: Int,
        pageSize: Int,
    ): MediaPage<AudiobookCard> {
        if (serverId == null) {
            val result = catalogRepository.allBooks(
                query = null,
                sort = BookSort.RECENT,
                page = page,
                pageSize = pageSize,
                formats = audioOnly,
            )
            val value = (result as? Outcome.Success)?.value
                ?: return MediaPage(emptyList(), hasMore = false)
            return MediaPage(
                items = value.books.map { book ->
                    val copy = book.primary
                    AudiobookCard(
                        mediaId = "${copy.serverId}::${copy.bookId}",
                        title = book.title,
                        author = book.authorLine.takeIf { it.isNotBlank() },
                        artworkUri = book.coverUrl,
                    )
                },
                hasMore = value.hasMore,
            )
        }

        val result = catalogRepository.books(
            serverId = serverId,
            shelfId = audiobookShelf(serverId),
            query = null,
            sort = BookSort.RECENT,
            page = page,
            pageSize = pageSize,
            formats = audioOnly,
        )
        val value = (result as? Outcome.Success)?.value
            ?: return MediaPage(emptyList(), hasMore = false)
        return MediaPage(
            items = value.books.map {
                AudiobookCard(
                    mediaId = "${it.serverId}::${it.id}",
                    title = it.title,
                    author = it.authorLine.takeIf { line -> line.isNotBlank() },
                    artworkUri = it.coverUrl,
                )
            },
            hasMore = value.hasMore,
        )
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

        val detail = (catalogRepository.detail(serverId, bookId) as? Outcome.Success)?.value
            ?: return null
        val acquisition = detail.acquisitions.firstOrNull { it.format == ContentFormat.AUDIOBOOK }
            ?: detail.primaryAcquisition
            ?: return null

        val localFile = downloadRepository.localFile(serverId, bookId)
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
        )
    }

    /** Grimmory exposes a server-side `file_type` shelf; BookOrbit doesn't. */
    private suspend fun audiobookShelf(serverId: String): String? =
        when (serverRepository.get(serverId)?.type) {
            ServerType.GRIMMORY -> "AUDIOBOOK"
            else -> null
        }

    private fun ReadingProgress.toCard() = AudiobookCard(
        mediaId = key,
        title = title.orEmpty(),
        author = author,
        artworkUri = coverUrl,
        progress = percent,
    )
}
