package net.dexxicon.reader.core.data.sync

/**
 * The outcome of one [net.dexxicon.reader.core.data.ReadingProgressRepository.syncProgress]
 * pass. [failures] is empty when every configured server reconciled cleanly.
 */
data class SyncReport(
    /** When the pass ran (epoch millis). */
    val at: Long,
    val failures: List<ServerSyncFailure> = emptyList(),
) {
    val ok: Boolean get() = failures.isEmpty()
}

/** One server that couldn't be reconciled this pass, and why. */
data class ServerSyncFailure(
    val serverId: String,
    val serverName: String,
    val reason: SyncFailureReason,
)

enum class SyncFailureReason {
    /** No network, or the server couldn't be reached. */
    OFFLINE,

    /** The session expired and can't be renewed without the user signing in. */
    SIGN_IN_REQUIRED,

    /** The server answered, but with an error. */
    SERVER_ERROR,
}
