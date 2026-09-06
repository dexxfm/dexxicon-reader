package net.dexxicon.reader.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.model.ReadingProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stores where each book was last left off. Position tokens are opaque (see [ReadingProgress]). */
@Singleton
class ReadingProgressRepository @Inject constructor(
    private val dao: ReadingProgressDao,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    fun observe(serverId: String, bookId: String): Flow<ReadingProgress?> =
        dao.observe(key(serverId, bookId)).map { it?.toDomain() }

    fun observeForServer(serverId: String): Flow<Map<String, ReadingProgress>> =
        dao.observeForServer(serverId).map { list ->
            list.associate { it.bookId to it.toDomain() }
        }

    suspend fun get(serverId: String, bookId: String): ReadingProgress? = withContext(io) {
        dao.find(key(serverId, bookId))?.toDomain()
    }

    suspend fun save(progress: ReadingProgress) = withContext(io) {
        dao.upsert(
            ReadingProgressEntity.fromDomain(progress.copy(updatedAt = System.currentTimeMillis())),
        )
    }

    suspend fun clear(serverId: String, bookId: String) = withContext(io) {
        dao.deleteByKey(key(serverId, bookId))
    }

    private fun key(serverId: String, bookId: String) = "$serverId::$bookId"
}
