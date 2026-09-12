package net.dexxicon.reader.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.datastore.CoverTapAction
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Phase 4 Stage E1 (issue #136) — backs the fully-portable slice of `:shared`'s Settings
 * screen: everything whose data layer ([AppContainer.appPreferences],
 * [AppContainer.downloadRepository], [AppContainer.appVersionName]) was already commonMain
 * before this class existed. Book Defaults, reading-sync status, and report-a-problem stay
 * out of this class — see [net.dexxicon.reader.shared.settings.SettingsScreen]'s doc comment
 * for why (tracked separately, issue #134).
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
}
