package net.dexxicon.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.crash.CrashReporter
import net.dexxicon.reader.core.common.crash.DiagnosticsArchive
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.media.AudiobookPlayer
import net.dexxicon.reader.core.media.PlayerUiState
import java.io.File
import javax.inject.Inject

/** A server whose session expired and needs the user to sign in again. */
data class SignInPrompt(val serverId: String, val displayName: String)

@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val player: AudiobookPlayer,
    private val crashReporter: CrashReporter,
    private val diagnosticsArchive: DiagnosticsArchive,
    tokenManager: TokenManager,
    serverRepository: ServerRepository,
    reauthCoordinator: ReauthCoordinator,
    downloadRepository: DownloadRepository,
) : ViewModel() {

    val playback: StateFlow<PlayerUiState> = player.state

    /** A crash report saved on a previous run, waiting for the user to send or discard it. */
    private val _pendingCrash = MutableStateFlow(crashReporter.pending().firstOrNull())
    val pendingCrash: StateFlow<File?> = _pendingCrash.asStateFlow()

    fun dismissCrash(delete: Boolean) {
        if (delete) _pendingCrash.value?.let(crashReporter::discard)
        _pendingCrash.value = null
    }

    /** Builds the zip of all app logs to attach to a crash email. Null if it can't be built. */
    suspend fun buildLogArchive(): File? = withContext(Dispatchers.IO) { diagnosticsArchive.build() }

    /** One-off notices (e.g. a download blocked by the storage limit) to show as a snackbar. */
    val messages: SharedFlow<String> = downloadRepository.messages

    val signInPrompts: StateFlow<List<SignInPrompt>> =
        combine(tokenManager.needsSignIn, serverRepository.servers) { ids, servers ->
            servers.filter { it.id in ids }.map { SignInPrompt(it.id, it.displayName) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Notification taps asking to re-authenticate a specific server. */
    val reauthRequests: SharedFlow<String> = reauthCoordinator.requests

    fun playPause() = player.playPause()
    fun dismiss() = player.stop()
}
