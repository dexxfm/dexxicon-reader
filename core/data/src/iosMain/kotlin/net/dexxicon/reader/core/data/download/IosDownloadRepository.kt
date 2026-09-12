package net.dexxicon.reader.core.data.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.Download

/** Honest "not supported yet" [DownloadRepository] — no iOS equivalent of `WorkManager`-backed
 * background downloads exists in this app today; see that interface's doc comment. Every read
 * reports "nothing downloaded", and [enqueue] reports why instead of silently doing nothing. */
class IosDownloadRepository : DownloadRepository {
    override val downloads: Flow<List<Download>> = flowOf(emptyList())
    override val usedBytes: Flow<Long> = flowOf(0L)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    override fun download(serverId: String, bookId: String): Flow<Download?> = flowOf(null)
    override suspend fun get(serverId: String, bookId: String): Download? = null

    override suspend fun enqueue(detail: BookDetail): EnqueueResult {
        _messages.tryEmit("Offline downloads aren't supported on iOS yet.")
        return EnqueueResult.NoFile
    }

    override suspend fun remove(serverId: String, bookId: String) = Unit
    override suspend fun localFile(serverId: String, bookId: String): String? = null
}
