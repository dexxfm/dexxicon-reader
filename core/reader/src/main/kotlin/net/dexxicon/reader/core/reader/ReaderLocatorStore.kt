package net.dexxicon.reader.core.reader

import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingProgress
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import javax.inject.Inject

/** Bridges Readium [Locator]s to the persisted [ReadingProgress] row for any reader. */
class ReaderLocatorStore @Inject constructor(
    private val progressRepository: ReadingProgressRepository,
) {
    suspend fun initialLocator(serverId: String, bookId: String): Locator? =
        progressRepository.get(serverId, bookId)?.locator
            ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

    suspend fun save(serverId: String, bookId: String, locator: Locator) {
        progressRepository.save(
            ReadingProgress(
                serverId = serverId,
                bookId = bookId,
                percent = locator.locations.totalProgression,
                locator = locator.toJSON().toString(),
            ),
        )
    }

    /**
     * Record the metadata snapshot for a book the reader just opened, so the Library
     * "Continue" shelves and KOReader refresh can work without a catalog round-trip.
     */
    suspend fun noteOpened(
        serverId: String,
        bookId: String,
        title: String?,
        author: String?,
        coverUrl: String?,
        format: ContentFormat,
        digestUrl: String?,
    ) {
        progressRepository.save(
            ReadingProgress(
                serverId = serverId,
                bookId = bookId,
                title = title,
                author = author,
                coverUrl = coverUrl,
                format = format,
                digestUrl = digestUrl,
            ),
        )
    }
}
