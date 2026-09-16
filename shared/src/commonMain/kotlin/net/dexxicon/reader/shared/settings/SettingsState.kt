package net.dexxicon.reader.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.datastore.PlayerPreferences
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.shared.di.AppContainer

/** Mirrors native's `SyncServerRow` (`feature/settings/KoSyncSettingsViewModel.kt`) —
 * one row per configured server in Settings' Reading sync section. */
data class SyncServerRow(
    val serverId: String,
    val name: String,
    /** BookOrbit / Grimmory sync through their own API; generic OPDS servers use KOReader. */
    val usesNative: Boolean,
    val configured: Boolean,
    /** The by-convention kosync URL for this server family (generic servers only). */
    val assumedUrl: String,
    /** A custom override URL, or "" when the assumed one is used. */
    val customUrl: String,
    val koSyncUsername: String,
    /** null = not checked, true/false = last verify result (generic servers only). */
    val verified: Boolean? = null,
    val verifying: Boolean = false,
    /** Epoch millis of the last successful progress sync, or null. */
    val lastSyncedAt: Long? = null,
)

/**
 * Phase 4 Stage E1 (issue #136) built out the fully-portable slice of `:shared`'s Settings
 * screen; Stage H (issue #145) filled in the rest once its data layer went commonMain too:
 * Book Defaults ([readerPreferences]/[playerPreferences], backed by
 * [AppContainer.readerPreferences]/[AppContainer.playerPreferences]) and Reading sync
 * ([syncRows], backed by [AppContainer.koSyncRepository]/[AppContainer.syncStateStore]).
 * Report-a-problem stays out of this class — no cross-platform diagnostics story has ever
 * been designed (Android's version emails a zip of Android-only log files); see
 * [net.dexxicon.reader.shared.settings.SettingsScreen]'s doc comment for how that's threaded
 * through as an optional platform callback instead.
 */
class SettingsState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    val preferences: StateFlow<AppPreferences> =
        container.appPreferences.preferences.stateIn(scope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    /** Bytes currently held by downloaded media. */
    val downloadUsedBytes: StateFlow<Long> =
        container.downloadRepository.usedBytes.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    val versionName: String get() = container.appVersionName

    fun setTheme(theme: AppTheme) {
        scope.launch { container.appPreferences.setTheme(theme) }
    }

    fun setBookViewDefault(mode: BookViewMode) {
        scope.launch { container.appPreferences.setBookViewDefault(mode) }
    }

    fun setBookSortDefault(sort: BookSort) {
        scope.launch { container.appPreferences.setBookSortDefault(sort) }
    }

    fun setCoverTapAction(action: CoverTapAction) {
        scope.launch { container.appPreferences.setCoverTapAction(action) }
    }

    fun setDownloadsWifiOnly(enabled: Boolean) {
        scope.launch { container.appPreferences.setDownloadsWifiOnly(enabled) }
    }

    /** [bytes] null = no limit. */
    fun setDownloadLimit(bytes: Long?) {
        scope.launch { container.appPreferences.setDownloadLimit(bytes) }
    }

    // --- Book Defaults: Audiobooks -----------------------------------------------------

    val playerPreferences: StateFlow<PlayerPreferences> = container.playerPreferences.preferences
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), PlayerPreferences())

    fun setSkipSilence(enabled: Boolean) {
        scope.launch { container.playerPreferences.setSkipSilence(enabled) }
    }

    fun setDefaultSpeed(speed: Float) {
        scope.launch { container.playerPreferences.setDefaultSpeed(speed) }
    }

    fun setSkipForwardSeconds(seconds: Int) {
        scope.launch { container.playerPreferences.setSkipForwardSeconds(seconds) }
    }

    fun setSkipBackSeconds(seconds: Int) {
        scope.launch { container.playerPreferences.setSkipBackSeconds(seconds) }
    }

    fun setSmartRewindSeconds(seconds: Int) {
        scope.launch { container.playerPreferences.setSmartRewindSeconds(seconds) }
    }

    // --- Book Defaults: Books (EPUB, comics, PDF) --------------------------------------

    val readerPreferences: StateFlow<ReaderDisplayPreferences> = container.readerPreferences.preferences
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ReaderDisplayPreferences())

    fun updateReaderPreferences(transform: (ReaderDisplayPreferences) -> ReaderDisplayPreferences) {
        scope.launch { container.readerPreferences.update(transform) }
    }

    // --- Reading sync --------------------------------------------------------------------

    private val verifyState = MutableStateFlow<Map<String, Pair<Boolean?, Boolean>>>(emptyMap())

    private val _syncRefreshing = MutableStateFlow(false)
    val syncRefreshing: StateFlow<Boolean> = _syncRefreshing

    val syncRows: StateFlow<List<SyncServerRow>> = combine(
        container.serverRepository.servers,
        verifyState,
        container.syncStateStore.lastSyncedAt,
    ) { servers, verify, lastSynced ->
        servers.map { s ->
            val (verified, verifying) = verify[s.id] ?: (null to false)
            SyncServerRow(
                serverId = s.id,
                name = s.displayName,
                usesNative = s.type.supportsNativeApi,
                configured = s.type.supportsNativeApi || !s.koSyncUsername.isNullOrBlank(),
                assumedUrl = s.assumedKoSyncUrl,
                customUrl = s.koSyncUrl.orEmpty(),
                koSyncUsername = s.koSyncUsername.orEmpty(),
                verified = verified,
                verifying = verifying,
                lastSyncedAt = lastSynced[s.id],
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [customUrl] blank → use the assumed URL (stored as null). */
    fun saveSync(serverId: String, customUrl: String, username: String, password: String) {
        scope.launch {
            val server = container.serverRepository.get(serverId) ?: return@launch
            container.serverRepository.save(
                server = server.copy(
                    koSyncUrl = customUrl.trim().trimEnd('/').takeIf { it.isNotBlank() },
                    koSyncUsername = username.trim().takeIf { it.isNotBlank() },
                ),
                password = null,
                koSyncPassword = password.takeIf { it.isNotBlank() },
            )
            verifySync(serverId)
        }
    }

    /**
     * Pull-to-refresh on Settings: reconcile reading progress with every server (native or
     * kosync, both directions) and re-verify the KOReader accounts.
     */
    fun refreshAllSync() {
        if (_syncRefreshing.value) return
        scope.launch {
            _syncRefreshing.value = true
            runCatching { container.progressRepository.syncProgress() }
            container.serverRepository.servers.first()
                .filter { !it.type.supportsNativeApi && !it.koSyncUsername.isNullOrBlank() }
                .forEach { server ->
                    setVerify(server.id, null, true)
                    val ok = runCatching { container.koSyncRepository.verify(server) }.getOrDefault(false)
                    setVerify(server.id, ok, false)
                }
            _syncRefreshing.value = false
        }
    }

    fun verifySync(serverId: String) {
        scope.launch {
            setVerify(serverId, null, true)
            val server = container.serverRepository.get(serverId)
            val ok = server != null && container.koSyncRepository.verify(server)
            setVerify(serverId, ok, false)
        }
    }

    private fun setVerify(serverId: String, verified: Boolean?, verifying: Boolean) {
        verifyState.value = verifyState.value.toMutableMap().apply {
            put(serverId, verified to verifying)
        }
    }
}
