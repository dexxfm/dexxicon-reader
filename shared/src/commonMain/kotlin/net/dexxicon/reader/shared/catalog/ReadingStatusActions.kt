package net.dexxicon.reader.shared.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import net.dexxicon.reader.core.serverapi.progress.ServerStatusUpdate

/**
 * Pushes a reading-status change to BookOrbit/Grimmory's own native APIs — a deliberately
 * narrow shared-UI subset of the native app's `BookActions.setReadingStatus`/`markFinished`
 * (`core/data/BookActions.kt`, androidMain, issue #84).
 *
 * The native version routes through `NativeProgressSync` (needs `SyncStateStore` for its
 * "last synced" bookkeeping — unrelated to this one operation, easy to add later if this
 * class ever needs it too) and also nudges the *local* reading-progress percentage via
 * `ReadingProgressRepository` so "Read"/"Unread" keep % and status in step. That repository
 * is entangled with `KoSyncRepository` (`Context`/`Settings.Secure`/raw `OkHttpClient`) and
 * `DownloadRepository` (WorkManager) — both genuinely Android-only — and `:shared` has no
 * local progress tracking or display to keep in sync with anyway. This is genuinely
 * everything needed right now, not a shortcut hiding a gap — revisit once shared UI needs to
 * show reading progress at all.
 *
 * [scope] should be a process-lifetime scope (see [net.dexxicon.reader.shared.di.AppContainer]),
 * not a screen-scoped `rememberCoroutineScope()` — a status push should survive the user
 * navigating away from the screen that started it, same as the native app's `@ApplicationScope`.
 */
class ReadingStatusActions(
    private val api: NativeProgressApi,
    private val serverRepository: ServerRepository,
    private val scope: CoroutineScope,
) {
    fun setReadingStatus(serverId: String, bookId: String, status: ReadingStatus) {
        scope.launch {
            val server = serverRepository.get(serverId) ?: return@launch
            runCatching { push(server, bookId, status) }
        }
    }

    /** "Mark as read" / "Mark as unread" — the quick toggle; also sets the status. */
    fun markFinished(serverId: String, bookId: String, finished: Boolean) =
        setReadingStatus(serverId, bookId, if (finished) ReadingStatus.READ else ReadingStatus.UNREAD)

    private suspend fun push(server: Server, bookId: String, status: ReadingStatus) {
        when (server.type) {
            ServerType.BOOKORBIT -> api.bookOrbitSetStatus(
                server.resolve("/api/v1/books/$bookId/status"),
                ServerStatusUpdate(status.toBookOrbit()),
            )
            ServerType.GRIMMORY -> api.grimmorySetStatus(
                server.resolve("/api/v1/app/books/$bookId/status"),
                ServerStatusUpdate(status.toGrimmory()),
            )
            else -> return
        }
    }
}

/** app status -> Grimmory `ReadStatus` enum name (UPPER). */
private fun ReadingStatus.toGrimmory(): String = when (this) {
    ReadingStatus.UNREAD -> "UNREAD"
    ReadingStatus.WANT_TO_READ -> "UNREAD" // Grimmory has no want-to-read
    ReadingStatus.READING -> "READING"
    ReadingStatus.ON_HOLD -> "PAUSED"
    ReadingStatus.REREADING -> "RE_READING"
    ReadingStatus.READ -> "READ"
    ReadingStatus.ABANDONED -> "ABANDONED"
}

/** app status -> BookOrbit `ReadStatus` (lower_snake). */
private fun ReadingStatus.toBookOrbit(): String = when (this) {
    ReadingStatus.UNREAD -> "unread"
    ReadingStatus.WANT_TO_READ -> "want_to_read"
    ReadingStatus.READING -> "reading"
    ReadingStatus.ON_HOLD -> "on_hold"
    ReadingStatus.REREADING -> "rereading"
    ReadingStatus.READ -> "read"
    ReadingStatus.ABANDONED -> "abandoned"
}
