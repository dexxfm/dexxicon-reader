package net.dexxicon.reader.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.crash.CrashReporter
import net.dexxicon.reader.core.common.crash.DiagnosticsArchive
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.shared.settings.SettingsScreen as SharedSettingsScreen

/**
 * Phase 4 Stage H (issue #145) — a thin platform entry point, same shape as
 * `feature/catalog/BookDetailScreen.kt`'s own doc comment: `:shared`'s `SettingsScreen`/
 * `SettingsState` hold the real logic and render (Appearance, Downloads, Book Defaults,
 * Reading sync, Format badges, About). `SettingsViewModel`, `KoSyncSettingsViewModel` and the
 * native-only `BookDefaultsScreens.kt` composables are gone — `:shared`'s own versions have
 * full parity now that `ReaderPreferencesStore`/`PlayerPreferencesStore`/`KoSyncRepository`
 * are all commonMain.
 *
 * [onReportProblem] is the one section `:shared`'s screen can't build itself — see its own
 * doc comment for why. [CrashReporter]/[DiagnosticsArchive] don't actually need Hilt (both
 * just take a `Context`, `@Inject`/`@Singleton` are there only for convenience elsewhere) so
 * this thin wrapper constructs them directly, same as [AndroidAppContainer.get] does for
 * everything else in this pattern — reading the same on-disk crash/log files the real
 * Hilt-provided [CrashReporter] singleton in `DexxiconApplication` already writes to.
 */
@Composable
fun SettingsScreen(
    versionName: String,
    onManageServers: () -> Unit = {},
    onOpenAudiobookDefaults: () -> Unit = {},
    onOpenBookDefaults: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { AndroidAppContainer.get(context) }
    val crashReporter = remember { CrashReporter(context) }
    val diagnosticsArchive = remember { DiagnosticsArchive(context, crashReporter) }
    val scope = rememberCoroutineScope()

    SharedSettingsScreen(
        container = container,
        onManageServers = onManageServers,
        onOpenAudiobookDefaults = onOpenAudiobookDefaults,
        onOpenBookDefaults = onOpenBookDefaults,
        onReportProblem = {
            scope.launch { sendProblemReport(context, versionName, diagnosticsArchive) }
        },
    )
}

/**
 * Opens the user's email app with device/app context prefilled and a zip of the app's logs
 * attached — no crash required. Nothing is sent until the user sends it.
 */
private suspend fun sendProblemReport(
    context: android.content.Context,
    versionName: String,
    diagnosticsArchive: DiagnosticsArchive,
) {
    val logsZip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        diagnosticsArchive.build()
    }
    val body = buildString {
        appendLine("Describe the problem here:")
        appendLine()
        appendLine()
        appendLine("---")
        appendLine("A zip of the app's logs is attached.")
        appendLine(CrashReporter.deviceBlock(context))
    }
    val attachment = logsZip?.let {
        runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", it,
            )
        }.getOrNull()
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(CrashReporter.CONTACT_EMAIL))
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Dexxicon Reader $versionName — problem report")
        putExtra(android.content.Intent.EXTRA_TEXT, body)
        if (attachment != null) {
            putExtra(android.content.Intent.EXTRA_STREAM, attachment)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(android.content.Intent.createChooser(intent, "Report a problem"))
    }
}
