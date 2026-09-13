package net.dexxicon.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.designsystem.theme.DexxiconTheme
import net.dexxicon.reader.crash.CrashReportSheet
import net.dexxicon.reader.crash.shareCrashReport
import net.dexxicon.reader.shared.App
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Phase 4 Stage I (issue #146) — trimmed to exactly what [AppShellViewModel]'s own doc comment
 * describes: a thin native overlay around `:shared`'s [App], not a second Scaffold/NavHost/
 * bottom-nav/mini-player parallel to it. `:shared`'s `App()` is now the *only* Compose UI tree
 * `:app` renders — same shape as iOS's `MainViewController`, which has never had a native shell
 * of its own to begin with (see `ContentView.swift`).
 */
@Composable
fun DexxiconApp(
    container: AppContainer,
    onOpenReader: OnOpenReader,
    shellViewModel: AppShellViewModel = hiltViewModel(),
) {
    val signInPrompts by shellViewModel.signInPrompts.collectAsStateWithLifecycle()
    val pendingCrash by shellViewModel.pendingCrash.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    pendingCrash?.let { report ->
        CrashReportSheet(
            report = report,
            onSend = { note ->
                scope.launch {
                    val logsZip = shellViewModel.buildLogArchive()
                    shareCrashReport(context, report, note, logsZip)
                    shellViewModel.dismissCrash(delete = true)
                }
            },
            onKeep = { shellViewModel.dismissCrash(delete = false) },
            onDiscard = { shellViewModel.dismissCrash(delete = true) },
        )
    }

    LaunchedEffect(Unit) {
        shellViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // :shared's own App() wraps its content in DexxiconTheme internally, but the overlay
    // pieces below (crash sheet, sign-in banner, snackbar) render as its siblings, not inside
    // it — without applying the same theme out here too, they'd fall back to Compose's
    // default MaterialTheme instead of matching the app's actual light/dark/system choice.
    val theme by container.appPreferences.preferences.collectAsState(initial = AppPreferences())
    val darkTheme = when (theme.theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    DexxiconTheme(darkTheme = darkTheme) {
        Box(Modifier.fillMaxSize()) {
            App(container = container, onOpenReader = onOpenReader, reauthRequests = shellViewModel.reauthRequests)

            Column(Modifier.fillMaxWidth()) {
                signInPrompts.forEach { prompt ->
                    SignInBanner(
                        displayName = prompt.displayName,
                        onClick = { shellViewModel.requestReauth(prompt.serverId) },
                    )
                }
            }

            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun SignInBanner(displayName: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Sign in to $displayName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Its session expired — tap to sign in again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
