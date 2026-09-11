package net.dexxicon.reader.core.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryAppSummary
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import io.ktor.client.plugins.ResponseException
import kotlinx.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls a server's own "continue reading" / "continue listening" lists — the ones its web
 * app shows — so the Library shelves fill in as soon as a server is added, without the user
 * having to open every book in this app first.
 *
 * Fetch + map only; persistence (and "don't clobber local progress") lives in
 * [ReadingProgressRepository.seedFromServer].
 */
@Singleton
class LibrarySeeder @Inject constructor(
    private val grimmory: GrimmoryBrowseApi,
    private val bookOrbit: BookOrbitBrowseApi,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    /** Every in-progress book the server knows about, as progress rows ready to persist. */
    suspend fun inProgress(server: Server): List<ReadingProgress> =
        when (val result = inProgressResult(server)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> {
                Log.w(TAG, "seed ${server.type} ${server.displayName} failed: ${result.error.message}")
                emptyList()
            }
        }

    /** Like [inProgress] but reports *why* it failed instead of swallowing it. */
    suspend fun inProgressResult(server: Server): Outcome<List<ReadingProgress>> = withContext(io) {
        try {
            Outcome.Success(
                when (server.type) {
                    ServerType.GRIMMORY -> grimmoryRows(server)
                    ServerType.BOOKORBIT -> bookOrbitRows(server)
                    else -> emptyList()
                },
            )
        } catch (e: ResponseException) {
            val status = e.response.status.value
            if (status == 401 || status == 403) {
                Outcome.Failure(DexxiconError.Unauthorized("HTTP $status"))
            } else {
                Outcome.Failure(DexxiconError.Unknown("HTTP $status", e))
            }
        } catch (e: IOException) {
            Outcome.Failure(DexxiconError.Network(e.message ?: "Network error"))
        } catch (e: Exception) {
            Outcome.Failure(DexxiconError.Unknown(e.message, e))
        }
    }

    private suspend fun grimmoryRows(server: Server): List<ReadingProgress> {
        val rows = buildList {
            addAll(grimmory.appInProgress(server.resolve("/api/v1/app/books/continue-reading?limit=50")))
            addAll(grimmory.appInProgress(server.resolve("/api/v1/app/books/continue-listening?limit=50")))
        }
        return rows.distinctBy { it.id }.mapNotNull { it.toProgress(server) }
    }

    private fun GrimmoryAppSummary.toProgress(server: Server): ReadingProgress? {
        val name = title?.takeIf { it.isNotBlank() } ?: return null
        val format = formatOf(primaryFileType)
        val coverPath = if (format == ContentFormat.AUDIOBOOK) {
            "/api/v1/media/book/$id/audiobook-cover"
        } else {
            "/api/v1/media/book/$id/cover"
        }
        return ReadingProgress(
            serverId = server.id,
            bookId = id.toString(),
            percent = readProgress?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            title = name,
            author = authors.joinToString(", ").takeIf { it.isNotBlank() },
            coverUrl = server.resolve(coverPath),
            format = format,
            digestUrl = server.resolve("/api/v1/books/$id/content"),
        )
    }

    private suspend fun bookOrbitRows(server: Server): List<ReadingProgress> {
        val rows = buildList {
            addAll(bookOrbit.dashboardScroller(server.resolve("/api/v1/dashboard/scrollers/continue-reading?limit=50")))
            addAll(bookOrbit.dashboardScroller(server.resolve("/api/v1/dashboard/scrollers/continue-listening?limit=50")))
        }
        return rows.distinctBy { it.id }.mapNotNull { card ->
            val name = card.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = card.files.firstOrNull { it.role.equals("primary", ignoreCase = true) }
                ?: card.files.firstOrNull()
            val format = formatOf(file?.format)
            // BookCard.readingProgress is the file's percentage on a 0–100 scale, same as
            // /books/files/{id}/progress.
            val pct = card.readingProgress?.let { (it / 100.0).coerceIn(0.0, 1.0) }
            ReadingProgress(
                serverId = server.id,
                bookId = card.id.toString(),
                percent = pct,
                title = name,
                author = card.authors.joinToString(", ").takeIf { it.isNotBlank() },
                coverUrl = server.resolve("/api/v1/books/${card.id}/cover"),
                format = format,
                digestUrl = file?.let { server.resolve("/api/v1/books/files/${it.id}/serve") },
            )
        }
    }

    private fun formatOf(raw: String?): ContentFormat = when (raw?.lowercase()) {
        "epub", "kepub" -> ContentFormat.EPUB
        "pdf" -> ContentFormat.PDF
        "cbx", "cbz", "cbr", "cb7" -> ContentFormat.COMIC
        "audiobook", "m4b", "mp3", "m4a", "opus", "ogg", "flac", "aac" -> ContentFormat.AUDIOBOOK
        "mobi", "prc" -> ContentFormat.MOBI
        "azw3", "azw" -> ContentFormat.AZW3
        "fb2" -> ContentFormat.FB2
        else -> ContentFormat.UNKNOWN
    }

    private companion object {
        const val TAG = "LibrarySeeder"
    }
}
