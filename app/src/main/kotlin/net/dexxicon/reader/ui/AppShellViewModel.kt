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
import java.io.File
import javax.inject.Inject

/** A server whose session expired and needs the user to sign in again. */
data class SignInPrompt(val serverId: String, val displayName: String)

/**
 * Phase 4 Stage I (issue #146) — trimmed down once native's own Scaffold/NavHost/bottom-nav/
 * mini-player (`DexxiconApp.kt`) were deleted in favor of hosting `:shared`'s `App()` directly
 * (see [MainActivity]): `playback`/`playPause`/`dismiss` moved to
 * `DexxiconApplication.wireMiniPlayer()`, which bridges the real [net.dexxicon.reader.core.media.AudiobookPlayer]
 * into `:shared`'s `AppContainer` directly rather than through this ViewModel — `:shared`'s own
 * `MiniPlayer` composable reads that, not this class. What's left here is genuinely
 * Android-only chrome with nowhere sensible to live inside `:shared`'s commonMain UI: crash
 * reporting, the session-expiry sign-in banner, and one-off snackbar notices — all still
 * rendered as a thin overlay around `:shared`'s `App()` in [MainActivity], not inside it.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val crashReporter: CrashReporter,
    private val diagnosticsArchive: DiagnosticsArchive,
    tokenManager: TokenManager,
    serverRepository: ServerRepository,
    private val reauthCoordinator: ReauthCoordinator,
    downloadRepository: DownloadRepository,
) : ViewModel() {

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

    /** The in-app [SignInBanner]'s own tap target — same coordinator a notification tap uses,
     * so both funnel through the one [reauthRequests] flow [MainActivity] forwards into
     * `:shared`'s `App()`. */
    fun requestReauth(serverId: String) = reauthCoordinator.request(serverId)
}
