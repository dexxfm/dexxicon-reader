package net.dexxicon.reader.core.data.media

import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.media.PlaybackProgressSink
import net.dexxicon.reader.core.model.ReadingProgress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackProgressSinkImpl @Inject constructor(
    private val progressRepository: ReadingProgressRepository,
) : PlaybackProgressSink {

    override suspend fun save(serverId: String, bookId: String, positionMs: Long, percent: Double?) {
        progressRepository.save(
            ReadingProgress(
                serverId = serverId,
                bookId = bookId,
                percent = percent,
                locator = "{\"position\":$positionMs}",
            ),
        )
    }
}
